// Optional output relocation for a full release build on a disk with enough free space.
// Changes no source set, compiler policy, engine choice or task dependency.
val releaseBuildLocation = System.getenv("NOTION_READER_RELEASE_BUILD_DIR")
    ?: error("Set NOTION_READER_RELEASE_BUILD_DIR to an absolute output directory")
require(java.io.File(releaseBuildLocation).isAbsolute)
allprojects {
    if (path == ":app") layout.buildDirectory.set(file(releaseBuildLocation))
}
