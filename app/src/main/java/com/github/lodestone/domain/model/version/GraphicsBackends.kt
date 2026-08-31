package com.github.lodestone.domain.model.version

/**
 * Which graphics backends a version's own code can drive.
 *
 * Minecraft 26.2 was the first release to ship `com.mojang.blaze3d.vulkan` beside the OpenGL one,
 * and to accept a `--graphicsBackend` argument choosing between them. That matters more here than
 * on a desktop: Android has no desktop OpenGL at all, so the OpenGL path costs a translation layer
 * and everything that comes with it, while the Vulkan path is the game talking to the vendor
 * driver directly.
 *
 * The two facts below are read off the version manifest rather than compared against a version
 * number. A release id is not a reliable ordering — snapshots, mod-loader manifests and Mojang's
 * own numbering changes all break it — whereas the libraries a manifest pins are exactly the code
 * that will be on the classpath.
 */

/**
 * True when this version carries the Vulkan renderer and understands `--graphicsBackend`.
 *
 * `org.lwjgl:lwjgl-vulkan` is the tell. It appears for the first time in the release that added
 * the backend, and it has to: the bindings are what `com.mojang.blaze3d.vulkan` is written
 * against, so a manifest that does not pin them cannot contain a Vulkan renderer no matter what it
 * is numbered.
 *
 * Deliberately not a check for `lwjgl-shaderc` or `lwjgl-vma`, which arrived alongside it. Those
 * are how the backend is implemented today and could reasonably be swapped out; the Vulkan
 * bindings could not be, and still leave a Vulkan renderer behind.
 */
val ResolvedVersion.supportsVulkanBackend: Boolean
    get() = libraries.asSequence()
        .mapNotNull(Library::coordinate)
        .any { it.group == LWJGL3_GROUP && it.artifact == LWJGL_VULKAN_ARTIFACT }

/** The value `--graphicsBackend` takes for each backend, as `PreferredGraphicsApi` deserialises it. */
enum class GraphicsBackend(val argument: String) {
    VULKAN("vulkan"),
    OPENGL("opengl"),
}

private const val LWJGL3_GROUP = "org.lwjgl"

private const val LWJGL_VULKAN_ARTIFACT = "lwjgl-vulkan"
