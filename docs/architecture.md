# Architecture and agent workflow

Diagrams follow the [Java agent workshop style](https://danieloh30.github.io/agentic-ai-java-workshop/01-first-agent/START_HERE/#step-3-understand-the-agent-loop): hand-drawn outlines, solid pastel fills, paper background, and dark labels. The SPA Architecture tab adds an expandable dialog.

## Delivery architecture

```mermaid
%%{init: {'look':'handDrawn','theme':'base','themeVariables': {'darkMode':false,'background':'#FFFCF5','primaryTextColor':'#1F1F1F','secondaryTextColor':'#1F1F1F','tertiaryTextColor':'#1F1F1F','textColor':'#1F1F1F','lineColor':'#4A4035','edgeLabelBackground':'#FFFCF5','clusterBkg':'#F5F5F0','clusterBorder':'#6B5B45','fontSize':'16px'},'flowchart': {'nodeSpacing':35,'rankSpacing':55,'padding':18}}}%%
flowchart TD
    Git(["Git release change"])
    CD(["Argo CD reconciles the Rollout"])
    R(["Argo Rollouts: 10% then 50%"])
    W["Stable and canary checkout"]
    I["Inventory dependency"]
    O["Metrics, logs, and trace-linked observations"]
    A(["InvestigationWorkflow.investigate()"])
    D{"Canary Decision Agent"}
    P{"Java policy gate"}
    GO["GO: advance current stage"]
    HOLD["HOLD: gather evidence or await approval"]
    NO["NO-GO: abort candidate"]
    Git --> CD --> R --> W --> I
    W --> O --> A --> D --> P
    P --> GO
    P --> HOLD
    P --> NO
    GO --> R
    NO --> R
    style Git fill:#D4E6F1,stroke:#2E6B8A,color:#1F1F1F,stroke-width:2px
    style CD fill:#D4E6F1,stroke:#2E6B8A,color:#1F1F1F,stroke-width:2px
    style R fill:#D4E6F1,stroke:#2E6B8A,color:#1F1F1F,stroke-width:2px
    style W fill:#FFF8DC,stroke:#C4A000,color:#1F1F1F,stroke-width:2px
    style I fill:#FFF8DC,stroke:#C4A000,color:#1F1F1F,stroke-width:2px
    style O fill:#FFF8DC,stroke:#C4A000,color:#1F1F1F,stroke-width:2px
    style A fill:#D4E6F1,stroke:#2E6B8A,color:#1F1F1F,stroke-width:2px
    style D fill:#FFE4CC,stroke:#B87333,color:#1F1F1F,stroke-width:2px
    style P fill:#FFE4CC,stroke:#B87333,color:#1F1F1F,stroke-width:2px
    style GO fill:#D8F0D8,stroke:#3D7A3D,color:#1F1F1F,stroke-width:2px
    style HOLD fill:#FFF8DC,stroke:#C4A000,color:#1F1F1F,stroke-width:2px
    style NO fill:#E8DCC4,stroke:#000000,color:#1F1F1F,stroke-width:2px
```

## Multi-agent investigation

```mermaid
%%{init: {'look':'handDrawn','theme':'base','themeVariables': {'darkMode':false,'background':'#FFFCF5','primaryTextColor':'#1F1F1F','secondaryTextColor':'#1F1F1F','tertiaryTextColor':'#1F1F1F','textColor':'#1F1F1F','lineColor':'#4A4035','edgeLabelBackground':'#FFFCF5','clusterBkg':'#F5F5F0','clusterBorder':'#6B5B45','fontSize':'16px'},'flowchart': {'nodeSpacing':35,'rankSpacing':55,'padding':18}}}%%
flowchart TD
    A(["InvestigationWorkflow.investigate()"])
    M(["MetricsAnalyst.analyze()"])
    MT["@Tool: compareRevisionMetrics()"]
    TEAM(["ResearchTeam: @ParallelAgent"])
    D(["DependencyInvestigator.investigate()"])
    DT["@Tool: traces and retry logs"]
    R(["ReleaseAnalyst.review()"])
    RT["@Tool: release changes and runbook"]
    C{"CanaryDecisionAgent.decide()"}
    P{"Java policy accepts evidence?"}
    YES["Return GO, HOLD, or NO-GO to Argo"]
    NO["HOLD: incomplete, stale, or unapproved"]
    A -->|"@SequenceAgent"| M
    M -->|"@ToolBox"| MT --> TEAM
    TEAM --> D --> DT --> C
    TEAM --> R --> RT --> C
    C --> P
    P -->|Yes| YES
    P -->|No| NO
    style A fill:#D4E6F1,stroke:#2E6B8A,color:#1F1F1F,stroke-width:2px
    style M fill:#D4E6F1,stroke:#2E6B8A,color:#1F1F1F,stroke-width:2px
    style MT fill:#FFF8DC,stroke:#C4A000,color:#1F1F1F,stroke-width:2px
    style TEAM fill:#E8DCC4,stroke:#000000,color:#1F1F1F,stroke-width:2px
    style D fill:#D4E6F1,stroke:#2E6B8A,color:#1F1F1F,stroke-width:2px
    style DT fill:#FFF8DC,stroke:#C4A000,color:#1F1F1F,stroke-width:2px
    style R fill:#D4E6F1,stroke:#2E6B8A,color:#1F1F1F,stroke-width:2px
    style RT fill:#FFF8DC,stroke:#C4A000,color:#1F1F1F,stroke-width:2px
    style C fill:#FFE4CC,stroke:#B87333,color:#1F1F1F,stroke-width:2px
    style P fill:#FFE4CC,stroke:#B87333,color:#1F1F1F,stroke-width:2px
    style YES fill:#D8F0D8,stroke:#3D7A3D,color:#1F1F1F,stroke-width:2px
    style NO fill:#E8DCC4,stroke:#000000,color:#1F1F1F,stroke-width:2px
```
