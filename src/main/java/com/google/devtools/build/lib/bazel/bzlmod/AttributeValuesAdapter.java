// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.devtools.build.lib.util.StringEncoding.internalToUnicode;
import static com.google.devtools.build.lib.util.StringEncoding.unicodeToInternal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSet;
import net.starlark.java.eval.Tuple;

/** Helps serialize/deserialize {@link AttributeValues}, which contains Starlark values. */
public class AttributeValuesAdapter extends TypeAdapter<AttributeValues> {

  private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

  @Override
  public void write(JsonWriter out, AttributeValues attributeValues) throws IOException {
    out.beginObject();
    for (Map.Entry<String, Object> entry : attributeValues.attributes().entrySet()) {
      out.name(entry.getKey());
      gson.toJson(serializeObject(entry.getValue()), out);
    }
    out.endObject();
  }

  @Override
  public AttributeValues read(JsonReader in) throws IOException {
    JsonObject jsonObject = JsonParser.parseReader(in).getAsJsonObject();
    Dict.Builder<String, Object> dict = Dict.builder();
    for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
      // The set of valid attribute names per repo rule is small and static, so interning them
      // helps reduce memory usage.
      dict.put(entry.getKey().intern(), deserializeObject(entry.getValue()));
    }
    return AttributeValues.create(dict.buildImmutable());
  }

  /**
   * Starlark Object Types Bool Integer String Label List (Int, label, string) Dict (String,list) &
   * (Label, String)
   *
   * <p>Values that JSON cannot represent unambiguously (floats, tuples, sets, out-of-int32-range
   * integers and dicts with non-string keys) are emitted in the tagged form produced by {@link
   * #tagged}. Such values only arise from {@code attr.value()} attributes.
   */
  private JsonElement serializeObject(Object obj) {
    if (obj.equals(Starlark.NONE)) {
      return JsonNull.INSTANCE;
    } else if (obj instanceof Boolean bool) {
      return new JsonPrimitive(bool);
    } else if (obj instanceof StarlarkInt starlarkInt) {
      try {
        return new JsonPrimitive(starlarkInt.toInt("serialization into the lockfile"));
      } catch (EvalException e) {
        return tagged(TAG_BIG_INT, new JsonPrimitive(starlarkInt.toString()));
      }
    } else if (obj instanceof StarlarkFloat starlarkFloat) {
      // Serialized via Double.toString, which round-trips exactly and covers infinities and NaN.
      return tagged(TAG_FLOAT, new JsonPrimitive(Double.toString(starlarkFloat.toDouble())));
    } else if (obj instanceof String || obj instanceof Label) {
      return new JsonPrimitive(serializeObjToString(obj));
    } else if (obj instanceof Dict<?, ?> dict) {
      if (!dict.keySet().stream().allMatch(k -> k instanceof String || k instanceof Label)) {
        JsonArray entries = new JsonArray();
        for (Map.Entry<?, ?> entry : dict.entrySet()) {
          JsonArray pair = new JsonArray();
          pair.add(serializeObject(entry.getKey()));
          pair.add(serializeObject(entry.getValue()));
          entries.add(pair);
        }
        return tagged(TAG_DICT, entries);
      }
      JsonObject jsonObject = new JsonObject();
      for (Map.Entry<?, ?> entry : dict.entrySet()) {
        jsonObject.add(serializeObjToString(entry.getKey()), serializeObject(entry.getValue()));
      }
      return jsonObject;
    } else if (obj instanceof Tuple tuple) {
      return tagged(TAG_TUPLE, serializeIterable(tuple));
    } else if (obj instanceof StarlarkSet<?> set) {
      return tagged(TAG_SET, serializeIterable(set));
    } else if (obj instanceof Iterable<?> iterable) {
      // ListType supports any kind of Iterable, including StarlarkLists. All of them are converted
      // to an equivalent StarlarkList during deserialization.
      return serializeIterable(iterable);
    } else {
      throw new IllegalArgumentException("Unsupported type: " + obj.getClass());
    }
  }

  private JsonArray serializeIterable(Iterable<?> iterable) {
    JsonArray jsonArray = new JsonArray();
    for (Object item : iterable) {
      jsonArray.add(serializeObject(item));
    }
    return jsonArray;
  }

  private Object deserializeObject(JsonElement json) {
    if (json == null || json.isJsonNull()) {
      return Starlark.NONE;
    } else if (json.isJsonPrimitive()) {
      JsonPrimitive jsonPrimitive = json.getAsJsonPrimitive();
      if (jsonPrimitive.isBoolean()) {
        return jsonPrimitive.getAsBoolean();
      } else if (jsonPrimitive.isNumber()) {
        return StarlarkInt.of(jsonPrimitive.getAsInt());
      } else if (jsonPrimitive.isString()) {
        return deserializeStringToObject(jsonPrimitive.getAsString());
      } else {
        throw new IllegalArgumentException("Unsupported JSON primitive: " + jsonPrimitive);
      }
    } else if (json.isJsonObject()) {
      JsonObject jsonObject = json.getAsJsonObject();
      if (jsonObject.has(TAG_KEY)) {
        return deserializeTagged(jsonObject);
      }
      Dict.Builder<Object, Object> dict = Dict.builder();
      for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
        dict.put(deserializeStringToObject(entry.getKey()), deserializeObject(entry.getValue()));
      }
      return dict.buildImmutable();
    } else if (json.isJsonArray()) {
      JsonArray jsonArray = json.getAsJsonArray();
      List<Object> list = new ArrayList<>();
      for (JsonElement item : jsonArray) {
        list.add(deserializeObject(item));
      }
      return StarlarkList.copyOf(Mutability.IMMUTABLE, list);
    } else {
      throw new IllegalArgumentException("Unsupported JSON element: " + json);
    }
  }

  // These keys start and end with the string escape sequence, so they can never collide with a
  // serialized user-provided dict key: serializeObjToString adds another layer of escaping to such
  // strings.
  @VisibleForTesting static final String TAG_KEY = "'type'";
  private static final String VALUE_KEY = "'value'";

  private static final String TAG_FLOAT = "float";
  private static final String TAG_BIG_INT = "bigint";
  private static final String TAG_TUPLE = "tuple";
  private static final String TAG_SET = "set";
  private static final String TAG_DICT = "dict";

  private static JsonObject tagged(String tag, JsonElement value) {
    JsonObject jsonObject = new JsonObject();
    jsonObject.add(TAG_KEY, new JsonPrimitive(tag));
    jsonObject.add(VALUE_KEY, value);
    return jsonObject;
  }

  private Object deserializeTagged(JsonObject jsonObject) {
    String tag = jsonObject.get(TAG_KEY).getAsString();
    JsonElement value = jsonObject.get(VALUE_KEY);
    switch (tag) {
      case TAG_FLOAT -> {
        return StarlarkFloat.of(Double.parseDouble(value.getAsString()));
      }
      case TAG_BIG_INT -> {
        return StarlarkInt.parse(value.getAsString(), 10);
      }
      case TAG_TUPLE -> {
        return Tuple.copyOf(deserializeElements(value.getAsJsonArray()));
      }
      case TAG_SET -> {
        return StarlarkSet.immutableCopyOf(deserializeElements(value.getAsJsonArray()));
      }
      case TAG_DICT -> {
        Dict.Builder<Object, Object> dict = Dict.builder();
        for (JsonElement pair : value.getAsJsonArray()) {
          JsonArray entry = pair.getAsJsonArray();
          dict.put(deserializeObject(entry.get(0)), deserializeObject(entry.get(1)));
        }
        return dict.buildImmutable();
      }
      default -> throw new IllegalArgumentException("Unsupported tagged value: " + tag);
    }
  }

  private List<Object> deserializeElements(JsonArray jsonArray) {
    List<Object> elements = new ArrayList<>(jsonArray.size());
    for (JsonElement item : jsonArray) {
      elements.add(deserializeObject(item));
    }
    return elements;
  }

  @VisibleForTesting static final String STRING_ESCAPE_SEQUENCE = "'";

  /**
   * Serializes an object (Label or String) to String. A label is converted to a String as it is. A
   * String that looks like a label is escaped so that it can be differentiated from a label when
   * deserializing, otherwise it is emitted as is.
   *
   * @param obj String or Label
   * @return serialized object
   */
  private String serializeObjToString(Object obj) {
    if (obj instanceof Label label) {
      String labelString = label.getUnambiguousCanonicalForm();
      Preconditions.checkState(labelString.startsWith("@@"));
      return internalToUnicode(labelString);
    }
    String string = (String) obj;
    // Strings that start with "@@" need to be escaped to avoid being interpreted as a label. We
    // escape by wrapping the string in the escape sequence and strip one layer of this sequence
    // during deserialization, so strings that happen to already start and end with the escape
    // sequence also have to be escaped.
    if (string.startsWith("@@")
        || (string.startsWith(STRING_ESCAPE_SEQUENCE) && string.endsWith(STRING_ESCAPE_SEQUENCE))) {
      return internalToUnicode(STRING_ESCAPE_SEQUENCE + string + STRING_ESCAPE_SEQUENCE);
    }
    return internalToUnicode(string);
  }

  /**
   * Deserializes a string to either a label or a String depending on the prefix and presence of the
   * escape sequence.
   *
   * @param unicodeValue String to be deserialized
   * @return Object of type String of Label
   */
  private Object deserializeStringToObject(String unicodeValue) {
    String value = unicodeToInternal(unicodeValue);
    // A string represents a label if and only if it starts with "@@".
    if (value.startsWith("@@")) {
      return Label.parseCanonicalUnchecked(value);
    }
    // Strings that start and end with the escape sequence always require one layer to be stripped.
    if (value.startsWith(STRING_ESCAPE_SEQUENCE) && value.endsWith(STRING_ESCAPE_SEQUENCE)) {
      return value.substring(
          STRING_ESCAPE_SEQUENCE.length(), value.length() - STRING_ESCAPE_SEQUENCE.length());
    }
    return value;
  }
}
