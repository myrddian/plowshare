# Delivering a change in slices

Read this when coordinating multiple dependent deliverables. A small cohesive
fix does not need an artificial task graph.

Model the work as nodes with dependencies. For each node, name its deliverable,
implementer, independent reviewer and concrete acceptance evidence. Define the
contract and relevant failure/race cases before splitting implementation files.
Assign disjoint file ownership when agents share a working tree.

The implementer produces the change and evidence; the reviewer checks the actual
diff, owning code and evidence. Findings return to the implementer for correction
and another review. A node closes only when both agree it is done. Review does
not transfer the implementer's responsibility for correctness to the reviewer.

Keep acceptance states separate:

- **Implemented:** the intended behavior and documentation exist.
- **Verified and reviewed:** relevant checks passed and findings are resolved.
- **Merged:** the reviewed change is on the target branch.
- **Deployed:** the intended artifact is running in the named environment.
- **Live validated:** the deployed system passed the relevant public-contract
  checks. Mock tests and healthy containers do not establish this state.

Match tested, reviewed, merged and packaged source trees. If integration changes
the result, review and verify the affected parts again. Report actual checks and
remaining limits without treating an earlier revision's result as current proof.

Use the root database-test policy: mock repository behavior by default; select
real PostgreSQL tests only for assertions that require it. Deployment work uses
the [operations guide](guides/deployment-operations.md) within the user's
authorized scope; a completed code node does not itself authorize a rollout.
