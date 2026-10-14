package eu.kanade.tachiyomi.ui.reader.manyue

/** Small-sample viewing judgments and measured facts; unknown is never a zero/star rating. */
internal object ManyueModelAssessment {
    data class Rating(val value: Int?, val basis: String) {
        init { require(value == null || value in 1..5) }
        fun display(): String = value?.let { "$it/5（初评）" } ?: "待测"
    }

    data class Assessment(
        val summary: String,
        val detail: Rating,
        val naturalness: Rating,
        val speed: Rating,
        val energy: Rating,
        val sustained: Rating,
        val evidence: String,
    ) {
        fun compact(): String = "细节 ${detail.display()} · 自然 ${naturalness.display()}\n" +
            "手机速度 ${speed.display()} · 能效 ${energy.display()} · 长读稳定 ${sustained.display()}"

        fun full(): String = compact() + "\n\n细节：${detail.basis}\n自然：${naturalness.basis}\n" +
            "速度：${speed.basis}\n能效：${energy.basis}\n长读：${sustained.basis}\n\n$evidence\n\n" +
            "5分最好，1分最弱；画质分是少量样本的主观初评，非真实细节恢复证明。" +
            "待测表示证据不足，不是0分。不计算掩盖缺项的综合分。"
    }

    fun forModel(model: ManyueAiModel): Assessment = when (model) {
        ManyueAiModel.QUALITY_REAL_ESRGAN, ManyueAiModel.FAST_REAL_CUGAN -> Assessment(
            summary = "现有质量基线；内部4×再缩到2×。效果较强，长读发热待修。",
            detail = Rating(4, "本轮彩漫/普通黑白漫少量裁剪中线条明显比原图插值清楚；细纹理可能被平滑。"),
            naturalness = Rating(4, "少量样本整体颜色自然；文字重构和密集网点仍可能产生伪细节/闪动。"),
            speed = Rating(null, "手机两张320×448测试图三轮推理293–585ms，波动大；不是正常大页阅读速度。"),
            energy = Rating(null, "用户报告长读发热/掉帧/变暗，未完成匹配的持续功耗/热机对照。"),
            sustained = Rating(null, "温控接入已修；本轮20页以上持续阅读和降温收益待验。"),
            evidence = "2026-10-14初评：两张程序图安卓对照、两张私有漫画主机裁剪。" +
                "默认保留原模型，支持与原生2×自行对照。没有高质量真值，不能宣称超越正版。",
        )
        ManyueAiModel.QUALITY_NATIVE2 -> Assessment(
            summary = "直接生成2×；少量样本接近基线，尚未证明手机提速。",
            detail = Rating(4, "本轮两个漫画裁剪与原模型接近；不是完整漫画专项增强训练。"),
            naturalness = Rating(4, "少量普通彩漫/黑白漫裁剪未发现明显损失；更多作品与网点滚动待验。"),
            speed = Rating(null, "手机两张小图三轮推理342–611ms，部分比基线慢，不能宣称更快。"),
            energy = Rating(null, "理论卷积量只减少约3.35%；电脑临时显存下降不等于手机更省电。"),
            sustained = Rating(null, "独立Native24次请求和数值对照通过；当前App长读/发热与实际绘制待验。"),
            evidence = "1000步冻结主干输出头迁移；PixelShuffle2，没有内部4×。" +
                "仅训练Pepper&Carrot，独立作品泛化未验。实验选项，原模型与缓存单独保留。",
        )
        ManyueAiModel.QUALITY_HYBRID -> Assessment(
            summary = "实验策略：正常用原质量，系统温控或可见页积压时尝试原生2×；均输出2×。",
            detail = Rating(null, "沿用两种质量权重，每页只用一种；混合阅读画质待验。"),
            naturalness = Rating(null, "已完成的图不随负载变化反复替换；不同页的模型效果可能略有差异。"),
            speed = Rating(null, "原生2×手机小图未证明更快；混轴及时显示收益待真机对照。"),
            energy = Rating(null, "未证明省电或降温；原生2×保持原主干，仅减少部分输出工作。"),
            sustained = Rating(null, "切换只在任务边界，恢复原模型需连续30秒无压力，防止频繁重载。"),
            evidence = "手机离线，本轮仅主机策略/缓存/代次验证。系统LIGHT以上或两个可见请求积压且等待超过2秒时切换。" +
                "缓存按实际模型隔离，完成页保留实际模型标签；严重温控仍遵守现有保护。默认继续原质量模型。",
        )
        ManyueAiModel.MOBILE_LITE -> Assessment(
            summary = "轻量保底；支持1–2×，增强较温和，低清细节补全较弱。",
            detail = Rating(2, "本轮同样两个漫画裁剪在2×/100%下主要接近插值原图，线条/文字增益明显弱于质量模型。"),
            naturalness = Rating(4, "同素材主机对照保留原图风格，改动温和；安卓滑动效果仍待验。"),
            speed = Rating(null, "小网络结构适合速度优先；本轮没有匹配安卓速度对照，不能给分。"),
            energy = Rating(null, "本轮持续能效未测；参数少不直接等于已测省电比例。"),
            sustained = Rating(null, "保留原权重与兼容路径，当前版本混合阅读长期测试待验。"),
            evidence = "2026-10-14核对当前Lite检查点与资产字节一致，使用同样两个私有漫画裁剪，未重新训练。" +
                "与两种质量模型分别选择。清晰图轻修/速度优先可尝试；" +
                "想明显补线条与低清细节优先对照质量模型。原图/OFF也保留在增强模式。",
        )
    }
}
