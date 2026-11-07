package com.example.examplemod;

/**
 * Routing policy governing how executor candidates are selected by the resolver.
 */
public enum RoutingPolicy {
    /** Generic Player-like executor is the default. Specialized adapters are selected only if no generic matches. */
    GENERIC_FIRST,
    /** Specialized mod adapters take precedence over generic player executors. */
    SPECIALIZED_FIRST,
    /** Strictly require a generic player executor; specialized adapters are never chosen. */
    GENERIC_STRICT,
    /** Capability handlers only. Standard clicks will not route to capability handlers unless this policy is active. */
    CAPABILITY_ONLY
}
