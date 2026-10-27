# Upstream Native Source

`marco` is a Git submodule of Marco Calautti's
[Datalog Diverse Why-Provenance repository](https://gitlab.com/mcalautti/datalog-diverse-why-provenance),
pinned to commit `4873700e11412c9242b6249f7aea0f488982f8ef`.

GitHub's automatically generated `marco @ 4873700` link may lead to a missing
page because the submodule is hosted on GitLab. Use these direct links to
browse the pinned revision:

- [Source tree](https://gitlab.com/mcalautti/datalog-diverse-why-provenance/-/tree/4873700e11412c9242b6249f7aea0f488982f8ef)
- [License](https://gitlab.com/mcalautti/datalog-diverse-why-provenance/-/blob/4873700e11412c9242b6249f7aea0f488982f8ef/LICENSE)
- [Native solver build instructions](https://gitlab.com/mcalautti/datalog-diverse-why-provenance/-/blob/4873700e11412c9242b6249f7aea0f488982f8ef/why-sat/README.md)

From the project root, initialize the source with:

```bash
git submodule update --init external/marco
```

The project's `code/c` and `code/java/native` symlinks use this checkout.
Initialize only `external/marco`: the pinned upstream revision has an incomplete
nested submodule configuration, so recursive initialization can fail. See the
[main README](../README.md#upstream-native-source) for further setup details.
