package dev.app8.cloud

/** A screen to warm into the cache ahead of render, optionally pinned to a version. */
public data class PrefetchTarget(val id: String, val version: String? = null)
