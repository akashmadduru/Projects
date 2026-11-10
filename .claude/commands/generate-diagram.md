---
description: Generate one or more architecture diagrams via the Diagram Agent's menu (HLD, LLD, Component, Sequence, Flow, State, Deployment, C4, ER, AWS, Auth Flow, API Flow, Event Flow, Microservice Communication, Folder Structure, Class, Activity, Custom).
argument-hint: [optional: diagram type number(s)/name(s) and subject; menu shown if omitted]
agent: diagram-agent
pipeline: false
---

# /generate-diagram

## Steps

1. Present Model Selection — default Haiku; recommend Sonnet/High budget if generating a
   multi-diagram bundle spanning the whole system topology.
2. Present Token Budget — default Medium; recommend High for multi-diagram bundles.
3. Invoke Diagram Agent (`agents/diagrams/diagram-agent.md`). If `$ARGUMENTS` doesn't
   already specify a type, present its menu:
   ```
    1 High Level Design        11 ER Diagram
    2 Low Level Design         12 AWS Architecture
    3 Component Diagram        13 Authentication Flow
    4 Sequence Diagram         14 API Flow
    5 Flow Diagram             15 Event Flow
    6 State Diagram            16 Microservice Communication
    7 Deployment Diagram       17 Folder Structure
    8 C4 Context               18 Class Diagram
    9 C4 Container              19 Activity Diagram
   10 C4 Component              20 Custom
   ```
4. Diagram Agent gathers real context for the selected type(s) and produces Mermaid
   source, PlantUML where beneficial, a Markdown narrative, and PNG/SVG if the
   environment supports rendering.

## Output

Diagram source file(s) and narrative, saved under `docs/diagrams/`, plus Standard
Output Contract listing every produced file path.
