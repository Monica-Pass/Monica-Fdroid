# 紧凑凭据详情

账号、密码沿用连续分组，横向排列图标、标签/内容和复制/显隐，取消冗余更多按钮。组内垂直边距从16dp收紧为10dp，密码强度与内容对齐。仅凭据使用紧凑样式。

点击整行内容记录触点，将既有 DropdownMenu 的零尺寸锚点移至触点。继续由原生组件负责动画、屏幕避让和菜单滚动；键盘/无障碍无触点操作回退至字段锚点。原有复制、显隐、大字、二维码、Send操作继续复用。

参考本地 Keyguard 的 FlatDropdownLayout/FlatItemLayout 和 popup 布局。可编辑设计在 ../design/compact-credential-316/canvas.json；本地链接 http://127.0.0.1:5186/compact-credential.html 。

测试包含触点两次点击的实际屏幕坐标变化、菜单边界、复制回调、大字体复制/显隐、旧单账号、附加账号的全部密码行及保存重开。详细构建/测试记录保存在工作区 .codex-tasks/20261003-compact-credential-detail/ 。未修改存储模型。

最终结果：普通版、F-Droid均打包成功，各5项设备测试通过；公共API32模拟器用户与系统设置已恢复。
