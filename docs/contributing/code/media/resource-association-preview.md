# 文件识别与关联预览

`ResourceAssociationPreviewBuilder` 是无数据库写入、无网络请求的预览服务。
输入为已扫描或浏览的视频项、调用方选定的剧集目录、已保存的确认/忽略决策，以及扫描根上的确认规则。
输出保留输入资源身份、候选标题、类型化集号、候选目标和可见状态。

文件名与可选文件夹名称交给现有 `RawTitleParser`，不另建解析引擎。
解析器未提取标题时，保留原文件夹名和不含扩展名的文件名作为搜索建议，不据此确认归属。
`titleSuggestions` 可用于既有 `SubjectSearchRepository`，标题本身不证明 Bangumi 身份。
剧集目录使用 `ResourceEpisodeOption(target, sort)`：调用方明确选择按连续集号或季内集号匹配，
SP/OVA 与正片保持各自的 `EpisodeSort` 类型。多个季度具有相同集号时保留所有候选。
字幕等非视频不进入匹配；已解析出的多集文件标记为 `MULTIPLE_EPISODES`，没有视频分段模型。

首次扫描不提供确认规则，唯一候选也仅为 `SUGGESTED`。
用户可逐项修正、忽略，或明确指定文件与剧集的顺序后调用 `assignInOrder`，包括跨季拆分。
有多个文件对应同集时自动关联采取保守冲突处理；用户仍可明确确认同集的多个版本或来源。
提交时将输入条目、目标 ID 和可选完整种子路径转换为关联用例的选择项。
预览不写绑定、进度或选源偏好。

`ConfirmedResourceMatchingRules.encode()` 的 JSON 存入 `LibraryScanRoot.matchingRuleJson`。
规则仅在用户确认适用来源、精确父容器和集号映射后保存；空 `acceptedTitles` 表示用户明确接受该容器内所有标题。
规则按来源 ID、父资源 ID、类型化集号及可选标题约束匹配。追加文件仅在一个已确认规则命中、
本批次无竞争文件且已保存决定中没有同来源目标占用时成为 `AUTO_ASSIGNABLE`。
即便多个规则指向同一目标，也保留歧义，不按规则顺序选第一个。
来源未在根目录文件上附带父引用时，扫描器应通过 `ResourcePreviewInput.parentReference` 传入实际目录引用。
未知 JSON 版本或无效规则由解码抛错，调用方展示规则不可用并回到普通建议，不得按默认规则静默继续。

调用方必须加载扫描根范围内的全部已确认绑定和忽略记录，转换为 `ProtectedResourceDecision`。
确认绑定，包括手工纠正，和忽略记录优先于识别结果。资源重新定位后，按数据库稳定资源记录关联到当前引用，
再构造 `ResourceFileIdentity`；不能仅用旧路径查找保护记录。
预览是快照：实际提交自动项前，仓储应在事务中重新检查扫描版本、绑定和忽略状态，避免过期预览覆盖手工修改。
只有 `AUTO_ASSIGNABLE` 可参与规则驱动提交，其余待处理状态均需要用户确认。
