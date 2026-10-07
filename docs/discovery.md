# Source discovery

`SourceDiscovery` queries GitHub repository search, prioritizing Arabic-related queries and capping proposals at 250 per run. It stores each repository as a disabled `PAUSED` source proposal. Discovery results are untrusted metadata: operators must verify repository ownership, package ID, release endpoint, host allowlist, and certificate fingerprint before enabling or retrying. The system deliberately does not count raw search hits as operationally discovered/accepted sources. GitHub API quotas and relevance mean the 250/day goal is not demonstrated.
