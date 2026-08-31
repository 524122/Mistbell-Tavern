URL: https://docs.tavoai.dev/cn/guides/advanced-rendering/
STATUS: 200

用户手册

复制本页

🖥️ 高级前端渲染（Web）

高级前端渲染

开启 高级前端渲染  (Advanced Rendering，以下简称 AR) 可以让聊天页面渲染标准的 HTML 与 CSS，以支持非常强大且灵活的页面美化。

如何开启

打开主界面

点击左上角打开左侧边栏

点击底部 更多

点击 设置

点击 高级前端渲染

打开 高级前端渲染 开关

如何使用

一个简单的例子：HTML 代码
在聊天页改写任一气泡，将以下内容粘贴进去：

```
这是一行有 <span style="color: red">红色</span> 的字
同时也有 <strong>粗体</strong>
有时候还能看到图片 <img className="max-w-md w-full" src="/static/images/docs/upload-wikimedia-org-0c3ce2fd3bba.jpg" alt="" />
```

JavaScript 支持

长记忆

想象一下，你和AI聊了很多次，每次聊到的东西都能被记住，并且它可以在未来的对话中提到。这就是**长记忆**。它让AI记住你说过的事情，不会一对话就忘记，你的偏好、兴趣、重要的细节都能被保留下来，随时能被回忆起。

工具调用

让兼容的聊天模型使用 Tavo 的原生和 TavoJS 内置工具。