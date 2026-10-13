package ca.mpreg.webgpuviewer.viewer
// No Android trace in host lifetime probe; the production upload body remains unchanged.
object ScrollTrace {
    inline fun <T> section(name: String, block: () -> T): T = block()
    fun counter(name: String, value: Long) {}
}
