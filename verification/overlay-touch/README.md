# 真实悬浮触摸与停止回归

当前0.28：同字节D973D720…校验包、原15秒恢复门槛、无失败手势重试。Endurance900约661878ms、1606项/391手势后RIGHT固定聊天未恢复；Keyboard29/6后KEYBOARD_DRAG_RIGHT当前目标0被拒，均FAIL。两次失败保持内分别确认原主29563/9771存活后只发TOUCH_INSPECTED观察ACK，不当成功停止外检。正式取时竞态隔离通过不能替代真机失败，晚到T08的相对时间只属推断，见 `../../scope-time-test-2026-10-25.md`。0.27同包Endurance923/224亦FAIL，未独立跑Keyboard，见scope-clock报告；以下均历史。

当前0.26：只给数字输出增加renderDisposition/renderQueueMs，原触摸门槛不变；accept/total不再含延后UI，缺失render需结合合并/取消状态，不能当0ms或跨版性能改善。Keyboard57/13通过、原主存活停止外检；Endurance35/6、第7步目标空集被拒仍失败。正式16项离屏回归不能替代真机结果，详见 `../../scope-render-test-2026-10-25.md`；本轮D973D720…与旧1E052B36…不同校验包，以下历史。

当前0.25：TraceWindows显式开启实际服务的默认关闭ScopeQueryTrace，冻结时复制最多64条数字阶段记录；连接/销毁/close reset，不增生产测试IPC或变更原断言。Entry并发可未完成，-1保留/快照非原子/同步render不含后续全部布局。Keyboard57/13通过，Endurance约309秒775/187、第188步当前目标空集被拒；失败保持自然到期，不ACK。请求3682 worker120、验证后135、render86、total224的真实分段见 `../../scope-query-timing-test-2026-10-25.md`；本轮1E052B36…与旧939A…不同包，不能称稳定性修复。以下历史。

当前0.24：同一939A7241…校验APK在新正式类上，Endurance900约389秒1048/254、外42/内42后真实点击未打开卡片；Keyboard第8步当前目标空集、拒绝注入，33/7失败。不是十五分钟通过或原Keyboard57可继承；失败保持各自原主存活观察ACK，不补发操作。续期调度隔离11通过不替代这些失败，见 `../../scope-cadence-test-2026-10-25.md`。以下为历史。

当前0.23见 `../../owned-window-test-2026-10-25.md`：新增实际ownID已经登记的初始断言，窗口数值环增加ownKnown/retainedOwnIds，不改原触摸/来源/时限门槛。Keyboard57/13通过、原主存活停止外检；Endurance900第8步准确当前目标空集、点击被拒，40/7仍失败。实际附着立即ownKnown、登记后误INVALIDATE未在可用日志缓冲复现，但范围失效/重建仍在，未完成十五分钟。以下0.22及更早“当前”均为历史。

当前0.22见 `../../frame-retry-test-2026-10-25.md`：正式修复未发布尝试的hash抑制，隔离33通过不替代真机恢复；同一线程采样测试包在0.21以53/11、0.22以111/25失败，十五分钟/整体稳定性仍未通过。后者有两次worker空闲、主线程几何/布局点采样，不能推断整段查询耗时。以下旧“当前/最新”均为历史证据。

新增数值观察实际结果见 `../../overlay-window-journal-test-2026-10-25.md`：同包Keyboard56/13与原主存活停止外检通过，但Endurance900约574秒、第362步失败；当前表自有记录0且实际View.detach，范围过期后未交付新证书，根本耗时仍未分离。不是稳定性修复，不关闭旧失败；正式0.21指纹未变，最新解析回归574项。

最新0.21结果见 `../../scope-renewal-test-2026-10-25.md`：Endurance900在0.20/0.21两轮均提前失败（目标不确定/外侧锚点效果），本轮Keyboard也在第8步目标不确定时失败。没有通过十五分钟或本轮Keyboard，不用旧0.20绿灯替代。后加外侧失败数值诊断不改变原判据，尚未实际触发；只认T08的旧返回条件不证明原滚动位置，后续需补足证据。

当前0.20见 `../../overlay-anchor-test-2026-10-25.md`：加强Keyboard新增两侧收起锚点断言，先在0.19实际失败1165→786，正式修复后两轮各56通过，默认Touch另56通过。0.19旧54和首轮未定位右拖失败仍见 `../../keyboard-overlay-test-2026-10-25.md`，不当全部修复；历史Touch/Keyboard测试APK分别另存 `build/verification/overlay-touch/touch-019-verified.apk`、`keyboard-019-verified.apk`。

仅独立instrumentation APK，不编入产品。链接与装机包一致的正式编译类，使用实际ChatGateService/AssistOverlay/AssistantCaptureService；不注入分析、Provider、来源或消息，不程序化performClick或改变expanded来冒充触摸。无真实密钥/云端/局部许可，开始时无采集。

## 构建与运行

先有正式app编译类和相同装机版本，运行 `verification/overlay-touch/build.ps1`，正常安装 `build/verification/overlay-touch/overlay-touch.apk`。再执行：

```powershell
& .\verification\overlay-touch\run.ps1 -Serial 9bafde2f
```

推荐加 `-VerifyInputTarget`：每步注入前核对当前InputDispatcher中自有UID/原主PID、display0、唯一可用悬浮窗口的frame与touchableRegion。自有操作起点/内部滚动终点必须命中，外侧/返回/输入框起点不得命中；不符就不发手势，不自动重试。只解析首个准确的当前Dispatcher Windows表，排除历史ANR/Connections复制；纯合成回归 `InputTargetParserRegression.ps1` 当前564项。未知格式保守拒绝，不是所有ROM兼容、原子触摸接收或微信身份认证；查询耗时不当触摸/OCR/AI耗时。仅记录阶段和自有几何/计数，不持久化原始dump。

较长交互观察使用 `-Variant Endurance -EnduranceSeconds 900 -VerifyInputTarget`。时长限定60–1800秒，默认900；完整左右两轮为一组，完成当前组后才结束，所以可能超过指定秒数。重复真实拖动、展开、外侧滚动、内侧滚动、收起和返回，最后仍真实点停止并做原主存活外检。不会输入/发送、切换输入法、改屏幕超时/电源设置或请求模型。

每组开始/完成仅记录数值：存活主/工作PID与启动身份、两个进程累计CPU/PSS、主进程Java已用/native分配、正式处理轮数；PSS使用公开API，可能受系统缓存影响，采样间隔由交互组耗时决定。主队列每250ms最多一个待完成心跳，按实际完成计算p50/p95/最大；样本在测试内存中，不记录任务内容。测试自身和ADB观察也有开销，不能把结果当生产无测试时的基线、微信帧率、新消息/AI延迟、耗电或已排除泄漏；USB充电时尤其不能验续航。Counter解析复用 `verification/owner-lifecycle/ProcessCpuSnapshot.java`，仅编入测试APK。

0.20原锚点验证测试包已另存 `build/verification/overlay-touch/anchor-020-verified.apk`（F1A14A60…），避免后续测试扩展改写历史指纹。新增Endurance不改变产品APK；实际结果须以当次终态、外检和完整数值记录为准，不因新增模式就宣称稳定性通过。

原键盘模式加 `-Variant Keyboard`：只聚焦显示/收起原输入法，不输入/发送，不改默认键盘。仅此分支调用只读composer证明并用自有球alpha触发新帧来定位输入区；不直接读Image。Back前电脑另核对微信焦点和mInputShown标志，停止外检新增默认输入法一致性核对。`-HoldFailure`可在失败后保留最多90秒观察自有布局元数据；不是自动重试。0.19一次目标不命中后实际进入该等待，但外检未在保持期内完成，不声称观察到了存活保持阶段；原始首轮右拖原因仍未全部查明。

脚本启动唯一instrumentation，按阶段正常打开自有MainActivity；等待普通设置开启本工具范围服务、普通MediaProjection授权，然后正常打开既有T01–T08测试聊天。不发送消息、不改输入法、云端或其它服务。不要为等待超时另起同一测试或强停原进程；检查原句柄和实际阶段。

原生只发布自有View的实际屏幕坐标、阶段和序号。电脑每次确认微信焦点、白名单阶段、坐标及手势类型后，用ADB真实tap/swipe，再确认该序号已完成。ACK不是通过依据；原生另检查布局、实际scrollY、OCR恢复和固定样本位置等。运行中不使用UiAutomator读取微信层级。

可选 `-TraceWindows` 只在测试APK内观察自有View的attach/detach和每100ms的范围/窗口状态，最多留128条数值环形记录；记录当前/已知自有窗口ID、来源窗口数字ID、generation、证书/在途请求ID、epoch和年龄，不记录标题、状态原文、节点正文或图像。新A656FB29…包将STAGE标记异步排到主线程，不再同步等观察后发命令；标记执行可能晚于输入开始，不是输入前原子证明。旧3C8ADC75…同步观察另存 `build/verification/overlay-touch/journal-sync-021-verified.apk`。观察仍有扰动，不能把未复现当修复；detach调用栈可因异步通知为空，不猜原因。

同一可选模式每50ms检查私有请求数值，只有请求超过自身期限，或范围已不可用且请求年龄≥100ms时，才采自有VM中唯一chat-scope-metadata线程和主线程的类/方法/行号；每请求最多一次、前后同Service/pending身份核对，不输出参数/正文，多个候选标AMBIGUOUS。记录scopeUnavailable/requestExpired、采样括号时间、epoch、状态；已不可用时采样不证明它导致初次失效，也不能由瞬时栈计算整段等待。冻结/finally停止定时器和监听；SAMPLER_ERROR/OBSERVER_ERROR均表示观测不完整，不能宣称定位成功。

输入前记录还增加当前Dispatcher/Windows表识别标志、窗口行数、同自有UID/PID原始记录数，以及其display/type/ID可识别计数；仅输出计数，不返回原始记录。574项合成回归包含原564项及10项诊断计数/历史副本/不返回原始字段检查，判据不放宽。

驱动拒绝时只发送测试期间动态注册的TOUCH_OBSERVE，立即冻结已有数值记录；它不完成手势ACK、不撤销检查、不改变产品状态或重试输入。失败/完成后由instrumentation线程导出WINDOW_JOURNAL；finally移除监听和回调、清空内存，正式产品无此广播入口。OBSERVER_ERROR表示观测不完整，不能声称已定位。旧失败Keyboard测试包2DD0A368…另存 `build/verification/overlay-touch/keyboard-failed-021-verified.apk`。

## 覆盖与边界

两侧各跨屏拖球、实际展开、卡片外拖动微信、卡片内滚动、实际收起和反向返回。外侧通道避开已知遮挡，比较收起后正式OCR中至少两个固定样本顶部变化≥24dp，并恢复读到T08；只在内存持有码/坐标，不保存原文历史或图像。不绕过卡片展开时释放图像的生产策略，不从Reader另取帧。

验证卡片NOT_FOCUSABLE/NOT_TOUCH_MODAL与实际不持有窗口焦点，展开时正文发布帧不增加，卡片内scrollY增加。不是所有触摸、键盘、横屏、大字体或真实模型长卡片通过。

最后真实点“停止”，观察正式owner的stopReason=1和resourcesReleased，再保持原主进程等待电脑独立核对主存活、工作/采集/显示/自有悬浮无、范围仍绑定；通过才发送检查ACK。不是系统UI撤权或精确回调次数计数。位置两项偏好在finally恢复；正式app没有新增测试IPC，两个接收动作仅instrumentation期间注册。

历史Touch两轮均56断言、14真实手势、2次外侧变化、2次内侧滚动，初三轮观察失败保留于 `../../overlay-touch-test-2026-10-25.md`。新测试包默认Touch另已复跑56通过；该分支不执行Image读取、截图、composer请求或alpha触发，Keyboard分支区别见前述。

结束后正常关闭自身范围服务、卸载 `com.codextemp.wechatcaptureprobe.overlaytouchverify`，独立核对状态，保留原键盘/旋转/其它服务和关闭的云端设置。不要用instrumentation退出造成的清空替代其退出前的外部核查。
