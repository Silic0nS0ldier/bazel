"""Rules for the copy-action benchmark.

Three copy mechanisms are compared:

  * `copy`  - `ctx.actions.copy`, one per input (the proposed action). Mnemonic `Copy`.
  * `spawn` - one `cp` spawn per input (status quo, per-input).       Mnemonic `CopySpawn`.
  * `batch` - a single spawn copying every input at once (batched).   Mnemonic `CopyBatch`.

Each mechanism comes in a file variant (`*_files`, output = `declare_file`) and a
directory variant (`*_dirs`, output = `declare_directory`). Their `srcs` are plain
labels, so the same rule copies either source artifacts (a source file, or a
source directory) or generated artifacts produced by the `make_file` / `make_tree`
generators below — this is how the driver contrasts source vs generated copying.

`make_file` / `make_tree` are each driven by a per-unit `seed` file; rewriting one
seed invalidates exactly that generated input (and forces its copy to re-run),
which is how the driver models partial-cache-miss states for generated scenarios.

The mnemonics let the driver read the per-mechanism executed-action count out of
the build's BEP `BuildMetrics`, so cache-miss counts exclude input generation.

Note: a *source* directory is a file-type artifact, not a tree artifact, so its
directoriness is unknowable at analysis time. `copy_dirs` still works on one: the
copy action accepts a source artifact wherever a directory output is declared and
verifies at execution that the input actually is a directory.
"""

# ---------------------------------------------------------------------------
# Input generators (for the "generated" scenarios)
# ---------------------------------------------------------------------------

def _make_file_impl(ctx):
    out = ctx.actions.declare_file(ctx.label.name + ".bin")
    ctx.actions.run_shell(
        inputs = [ctx.file.seed],
        outputs = [out],
        # Content is unique per file (marker) and changes with the seed, padded
        # with NULs to the requested size.
        command = """
            set -e
            out="$1"; seed="$2"; marker="$3"; size="$4"
            { printf '%s\\n' "$marker:$(cat "$seed")"; head -c "$size" /dev/zero; } \
                | head -c "$size" > "$out"
        """,
        arguments = [out.path, ctx.file.seed.path, ctx.label.name, str(ctx.attr.size)],
        mnemonic = "MakeFile",
        progress_message = "Generating file %{output}",
    )
    return [DefaultInfo(files = depset([out]))]

make_file = rule(
    implementation = _make_file_impl,
    attrs = {
        "seed": attr.label(allow_single_file = True, mandatory = True),
        "size": attr.int(mandatory = True),
    },
)

def _make_tree_impl(ctx):
    tree = ctx.actions.declare_directory(ctx.label.name + ".tree")
    ctx.actions.run_shell(
        inputs = [ctx.file.seed],
        outputs = [tree],
        command = """
            set -e
            dir="$1"; seed="$2"; count="$3"; size="$4"; marker="$5"
            header="$marker:$(cat "$seed")"
            for i in $(seq 1 "$count"); do
                { printf '%s\\n' "$header-$i"; head -c "$size" /dev/zero; } \
                    | head -c "$size" > "$dir/f$i.bin"
            done
        """,
        arguments = [
            tree.path,
            ctx.file.seed.path,
            str(ctx.attr.count),
            str(ctx.attr.size),
            ctx.label.name,
        ],
        mnemonic = "MakeTree",
        progress_message = "Generating tree %{output}",
    )
    return [DefaultInfo(files = depset([tree]))]

make_tree = rule(
    implementation = _make_tree_impl,
    attrs = {
        "seed": attr.label(allow_single_file = True, mandatory = True),
        "count": attr.int(mandatory = True),
        "size": attr.int(mandatory = True),
    },
)

# ---------------------------------------------------------------------------
# Shared helper: one artifact per `srcs` entry, whether a source file/directory
# or a generator target.
# ---------------------------------------------------------------------------

def _inputs(ctx):
    return [dep[DefaultInfo].files.to_list()[0] for dep in ctx.attr.srcs]

_SRCS = {"srcs": attr.label_list(allow_files = True, mandatory = True)}

# ---------------------------------------------------------------------------
# File mechanisms (output: declare_file)
# ---------------------------------------------------------------------------

def _copy_files_impl(ctx):
    outs = []
    for f in _inputs(ctx):
        o = ctx.actions.declare_file("copy/" + f.basename)
        ctx.actions.copy(input = f, output = o)
        outs.append(o)
    return [DefaultInfo(files = depset(outs))]

copy_files = rule(implementation = _copy_files_impl, attrs = _SRCS)

def _spawn_files_impl(ctx):
    outs = []
    for f in _inputs(ctx):
        o = ctx.actions.declare_file("spawn/" + f.basename)
        ctx.actions.run_shell(
            inputs = [f],
            outputs = [o],
            command = 'cp -L "$1" "$2"',
            arguments = [f.path, o.path],
            mnemonic = "CopySpawn",
            progress_message = "Copying (spawn) %{input}",
        )
        outs.append(o)
    return [DefaultInfo(files = depset(outs))]

spawn_files = rule(implementation = _spawn_files_impl, attrs = _SRCS)

def _batch_files_impl(ctx):
    # One spawn copies every input into a single output directory. Basenames are
    # unique by construction (the generator names inputs distinctly).
    out_dir = ctx.actions.declare_directory("batch")
    files = _inputs(ctx)
    args = ctx.actions.args()
    args.add(out_dir.path)
    args.add_all(files)
    args.use_param_file("@%s", use_always = True)
    args.set_param_file_format("multiline")
    ctx.actions.run_shell(
        inputs = files,
        outputs = [out_dir],
        command = """
            set -e
            args_file="${1#@}"
            {
                read -r dest
                while read -r f; do cp -L "$f" "$dest/${f##*/}"; done
            } < "$args_file"
        """,
        arguments = [args],
        mnemonic = "CopyBatch",
        progress_message = "Copying (batch) into %{output}",
    )
    return [DefaultInfo(files = depset([out_dir]))]

batch_files = rule(implementation = _batch_files_impl, attrs = _SRCS)

# ---------------------------------------------------------------------------
# Directory mechanisms (output: declare_directory)
# ---------------------------------------------------------------------------

def _copy_dirs_impl(ctx):
    outs = []
    for d in _inputs(ctx):
        o = ctx.actions.declare_directory("copy/" + d.basename)

        # Works for generated tree artifacts and for source directories (the
        # latter validated as actually-a-directory at execution time).
        ctx.actions.copy(input = d, output = o)
        outs.append(o)
    return [DefaultInfo(files = depset(outs))]

copy_dirs = rule(implementation = _copy_dirs_impl, attrs = _SRCS)

def _spawn_dirs_impl(ctx):
    outs = []
    for d in _inputs(ctx):
        o = ctx.actions.declare_directory("spawn/" + d.basename)
        ctx.actions.run_shell(
            inputs = [d],
            outputs = [o],
            # -L dereferences: sandboxed tree inputs are symlinks, and a real copy
            # must copy content, not reproduce the links.
            command = 'cp -RL "$1"/. "$2"/',
            arguments = [d.path, o.path],
            mnemonic = "CopySpawn",
            progress_message = "Copying (spawn) dir %{output}",
        )
        outs.append(o)
    return [DefaultInfo(files = depset(outs))]

spawn_dirs = rule(implementation = _spawn_dirs_impl, attrs = _SRCS)

def _batch_dirs_impl(ctx):
    out_dir = ctx.actions.declare_directory("batch")
    dirs = _inputs(ctx)
    args = ctx.actions.args()
    args.add(out_dir.path)
    for d in dirs:
        args.add(d.basename + " " + d.path)
    args.use_param_file("@%s", use_always = True)
    args.set_param_file_format("multiline")
    ctx.actions.run_shell(
        inputs = dirs,
        outputs = [out_dir],
        command = """
            set -e
            args_file="${1#@}"
            {
                read -r dest
                while read -r name path; do
                    mkdir -p "$dest/$name"
                    cp -RL "$path"/. "$dest/$name"/
                done
            } < "$args_file"
        """,
        arguments = [args],
        mnemonic = "CopyBatch",
        progress_message = "Copying (batch) dirs into %{output}",
    )
    return [DefaultInfo(files = depset([out_dir]))]

batch_dirs = rule(implementation = _batch_dirs_impl, attrs = _SRCS)
