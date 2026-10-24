<div class="chapter-meta">01 / LOCAL DEVELOPMENT / JAVA 25</div>

# Run the decision room

Start with a working UI and a small investigation. You will learn how the demo separates live model calls, offline evidence, and actual Kubernetes authority.

## 1. Check your tools

You need **JDK 25**, Git, and access to Maven Central. The wrapper downloads Maven; a separate Maven installation is optional.

```bash
java -version
git --version
```

The Java output should report version 25. If you installed Java with Homebrew on macOS, the launcher recognizes `/opt/homebrew/opt/openjdk@25` when `JAVA_HOME` is unset.

## 2. Get the project

```bash
git clone https://github.com/danieloh30/intelligent-canary-delivery.git
cd intelligent-canary-delivery
```

## 3. Choose an execution mode

=== "Real model, local evidence"

    Export your OpenAI API key through your normal secret-management workflow, then start the app:

    ```bash
    export OPENAI_API_KEY='your-key'
    ./demo.sh dev
    ```

    All four agents use **GPT-4o mini**. The evidence is a clearly labeled local fixture because the app is not connected to a cluster. This mode exercises model reasoning and Java tools without changing a rollout.

=== "Offline rehearsal"

    ```bash
    ./demo.sh rehearsal
    ```

    This mode makes **no model calls**. Deterministic fixture outcomes let you rehearse UI navigation, timing, approval, and evidence inspection. The UI labels them explicitly.

Open **http://localhost:8095**. Enter **`demo-local`** as the local presenter token. Set `DEMO_PORT` before starting if you need a different port.

!!! note "The API key stays on the server"
    The browser receives a presenter token, never the OpenAI API key. The token is stored in the current tab's session storage and sent in an Authorization header. This is a dedicated demonstration access mechanism, not a production identity system.

## 4. Run your first investigation

1. Select **Healthy release**.
2. Keep the policy on **Development / automatic**.
3. Select **Run investigation**.
4. Watch Metrics Analyst run first, Dependency Investigator and Release Analyst run together, and Canary Decision Agent synthesize the findings.
5. Open **Evidence explorer** to inspect the observation, recommendation, and policy result.

The policy should allow a healthy fixture to produce GO. In offline rehearsal, the result is deterministic. With a real model, inspect the actual findings and limitations rather than assuming a particular response.

## 5. Contrast HTTP health with dependency work

Select **Hidden retries** and run another investigation. The basic HTTP calculation passes, but the fixture contains increased dependency attempts. The deterministic rehearsal recommends NO-GO; live agents should investigate that evidence through their tools.

In **Live console**, select Agent activity to see bounded investigation events. Kubernetes pod sources are unavailable locally and say so explicitly.

## 6. Explore the architecture

Open **Architecture**, expand the multi-agent diagram, and trace the sequence through the parallel investigation stage. Press Escape to return to the page. Both diagram sizes retain the workshop's paper background and solid pastel fills.

<div class="checkpoint" markdown>

### Checkpoint

You can identify the current execution mode, run an investigation, inspect its evidence, and explain why a local result cannot authorize a Kubernetes rollout.

</div>

Continue to [install the Kubernetes demo](02-cluster.md), or inspect the [Java agent implementation](../reference/agents.md).
