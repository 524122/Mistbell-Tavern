URL: https://docs.tavoai.dev/cn/guides/api-setting/get-key/openai/
STATUS: 200

用户手册
API 与模型
获取密钥

复制本页

OpenAI API

想要使用 OpenAI 平台的AI模型能力，整个过程主要分为两步：

首先，在 OpenAI 平台注册一个账号；

其次，在账号内创建并保管好你的 API 密钥。

以下是 OpenAI 平台的完整入门流程。指引遵循“先注册，后取钥”的逻辑，带你从零开始，轻松上手。

1.电子邮箱 + 密码注册

流程：

打开注册页面（如 chat.openai.com/auth/signup或平台 API 地址 platform.openai.com/signup）。

在“Username + Password”区域输入邮箱地址和密码，点击“Continue”。

登录邮箱，点击 OpenAI 发来的验证链接完成邮箱验证。

按页面提示填写姓名、生日等信息，点击“Agree”即可开始使用。

特点： 无需依赖第三方账号，适合不希望绑定 Google/Microsoft 的用户。

注意： 如果之后要生成首个 API 密钥，仍需完成一次手机号验证（国内用户可使用短信平台接码）。

2.Google 登录

在注册页面点击 “Continue with Google” 按钮，通过 Google OAuth 完成授权后，无需额外设置密码即可直接登录与使用。

适用场景： 已有 Google 账号并希望快速注册、避免重复记密码。

登录一致性： 此后必须使用同一 Google 账户方式登录，不能再用邮箱+密码或其他方式登录同一账号。

3.Microsoft 登录

在注册页面点击 “Continue with Microsoft” 按钮，通过 Microsoft OAuth（Outlook/Hotmail/Live 帐号）授权完成注册。

适用场景： 已有 Microsoft 生态账号且希望与 Azure、Office 365 等服务关联。

注意： 与 Google 登录类似，仅能通过该 OAuth 入口进行后续登录。

4.Apple 登陆

点击“使用Apple 登陆”按钮，即可发起苹果登录流程。

在授权界面，用户可选择“隐藏我的邮箱”，确保用户真实邮箱不会泄露给第三方。注意：与 Google 登录类似，仅能通过该 OAuth 入口进行后续登录

用户通过 Face ID/Touch ID 或输入 Apple ID 密码确认后，苹果会返回一个授权码（authorization code）和 ID token 到 OpenAI ；OpenAI 后端使用该授权码向 Apple 的 token 端点交换用户身份令牌，验证成功后创建或登录对应的 ChatGPT 账号。

5.获取 API 密钥

访问OpenAI的官方网站注册一个账户或登录您现有的账户： https://platform.openai.com

点击右上角Start building

填写信息并创建

填写API名称生成你的 API Key

复制生成的API密钥，并安全地保存它

Volink API

想要使用 Volink 平台的AI模型能力，整个过程主要分为两步：

Claude API

想要使用 Claude 平台的AI模型能力，整个过程主要分为两步：