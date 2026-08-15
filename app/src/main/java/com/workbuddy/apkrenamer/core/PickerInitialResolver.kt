package com.workbuddy.apkrenamer.core

/**
 * 「目录选择器初始位置」（§7.7.1 / `DocumentsContract.EXTRA_INITIAL_URI`）的
 * **纯决策核心**：docId 层面的三级取位，零 Android 依赖。
 *
 * 「Uri → docId」由调用方经 [SafScanner.treeDocumentIdOf] 完成；OTG 记忆的读写
 * 经 [RulePrefs]（`otg_folder:<卷ID>`）。输入：
 * - [currentDocId]：当前生效目录的 docId；null = 本会话没有生效目录
 *   （例如启动时卷未插入，`restoreFolder` 已把 `KEY_TREE` 当脏记录清掉）；
 * - [rememberDocId]：按卷 ID 查 OTG 记忆的 docId，无记忆返回 null；
 * - [grantDocIds]：可读写持久化授权的 docId 列表（含主存储，本函数自行过滤）。
 *
 * 取位优先级：
 * 1. 当前目录在可移动卷上、且该卷记忆目录仍在当前授权子树内 → [Initial.Remembered]；
 * 2. 有当前目录 → [Initial.Current]；
 * 3. 无当前目录，但某可移动卷的授权仍覆盖其记忆目录 → [Initial.Remembered]
 *    （卷重插后无需重新授权即可直达）；
 * 4. 都取不到 → null（选择器回落系统默认位置）。
 *
 * 注意：分支 3 按 [grantDocIds] 的顺序取**第一个**命中；跨 authority 的授权
 * 若卷 ID 前缀相同，理论上可能误命中，但卷 ID 含磁盘序列号、实际不可碰撞。
 */
object PickerInitialResolver {

    sealed interface Initial {
        /** 用当前生效目录 */
        data object Current : Initial

        /** 用某可移动卷的 OTG 记忆目录（调用方按 volumeId 回查记忆里存的完整树 Uri） */
        data class Remembered(val volumeId: String) : Initial
    }

    fun resolve(
        currentDocId: String?,
        rememberDocId: (String) -> String?,
        grantDocIds: List<String>,
    ): Initial? {
        if (currentDocId != null) {
            val volume = VolumeLogic.volumeOf(currentDocId)
            val rememberedCovers = VolumeLogic.isRemovableVolumeId(volume) &&
                rememberDocId(volume)?.let { VolumeLogic.isDescendantOf(it, currentDocId) } == true
            return if (rememberedCovers) Initial.Remembered(volume) else Initial.Current
        }
        for (grant in grantDocIds) {
            val volume = VolumeLogic.volumeOf(grant)
            if (!VolumeLogic.isRemovableVolumeId(volume)) continue
            val remembered = rememberDocId(volume) ?: continue
            if (VolumeLogic.isDescendantOf(remembered, grant)) return Initial.Remembered(volume)
        }
        return null
    }
}
