# The panel

Open `http://127.0.0.1:18080/`. With no project yet, the panel shows a single card: **Add project**. Enter the
repository folder; it is indexed structurally in seconds and the code graph opens.

## Code graph

- A radial map: packages are arcs on the inner orbit (ticks outside an arc count its classes); opening a package fans
  its classes out on the next orbit, opening a class fans its methods out on the outer orbit.
- Real dependencies run through the middle as bundled curves. Selecting a node shows its own incoming and outgoing
  dependencies in the accent colour, with arrowheads.
- Memories (●) and rules (◆) attached to a node or inside it are shown on the map and next to its name, with counts.
- Click a node or its name to open and select it; the detail card lists everything an open node holds, filterable.
- Find any package, class or method with the search box (`/`); ↑ ↓ step through neighbours, Enter opens, Esc goes one
  layer up, F shows everything.
- `/universe.html` shows the same map full screen; `/universe.html?view=list` shows it as a tree list.

## Attach a memory or a rule to a node

Select a node and use the detail card:

- **Add memory** — this project or global. The memory points at the node (a directory, a file or a method), so it shows
  up on the node and in the agent's startup cards when a task matches it.
- **Add rule** — global, this project, or **this node**:
  - a package → the rule applies to everything in that directory;
  - a class or file → the rule applies to that file;
  - a method → the rule is delivered while the agent edits the method's file, and names the exact method
    (overloads are kept apart). The confirmation card says this in words before you approve.

Every rule is previewed and needs your typed approval before agents receive it.

## Other tabs

Overview · Memory (search, edit, archive, delete) · Pending (proposals waiting for your decision) · Rules ·
References (long-form notes linked from memories) · Jobs (last job, saved jobs, personal memory).
