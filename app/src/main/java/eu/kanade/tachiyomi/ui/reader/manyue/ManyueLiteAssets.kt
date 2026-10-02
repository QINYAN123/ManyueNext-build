package eu.kanade.tachiyomi.ui.reader.manyue

/** Exact verified release bytes; model revision also isolates cached reconstruction results. */
internal object ManyueLiteAssets {
    const val NATIVE_SHA256 = "d9a3e878ddc173efd4d366e9fadb21a78571153ea61d60fd554593f398f1ffb0"
    const val MODEL_REVISION = "e5a15c9b0c815cf1a2e8559b4687ca1baf2e1bd0f70b4b8ca071f2c73b42a343"
    val MODEL_SHA256: Map<String, String> = mapOf(
        "trunk.param" to "a0f0bc1d915161e3e0f8a3ae925ba6bdc5f84d2a3e2babc9465b52424655eda3",
        "trunk.bin" to "13b61c208831c3438cc53c01c8315c534fa3a11557fd12cd9db7cb81691dd410",
        "head.param" to "77a7f865b40d212cf0c3374c4e58ba0eecc01f2c17dda28e7a412a6aab195d4e",
        "head.bin" to "538c8a190ebfea28474ab95b59a40f2b2dc3a222445145e8c57eecd6ddd24dd8",
        "head.f32" to "9cd6b429375608b531c41d0bd6c51a7f8fa377e4cbc6d5d42428c8154c99ec50",
    )
}
