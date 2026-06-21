# Vibe Writing Android

这是 `article-editor.html` 的 Android 原生版本，不使用 WebView。

## 已实现

1. 原生标题和正文编辑器
2. 草稿与参考文件自动保存在本机
3. 智谱 GLM 自动续写
4. 手机端按钮接受下一句、下一段或忽略建议
5. 语音录制、智谱转写、按当前正文文风润色并插入
6. 大屏和双折叠设备使用左右双栏，小屏使用上下布局
7. API Key 通过 Android Keystore 加密保存

## 运行

用 Android Studio 打开 `android-app` 目录，等待 Gradle 同步完成，然后连接安卓设备运行。

项目要求：

* Android Studio Ladybug 或更新版本
* JDK 17
* Android SDK 35
* 最低 Android 8.0，API 26

首次打开应用后，点击右上角的设置，填写智谱 API Key。

## 语音流程

点击语音输入开始录音，再次点击停止。录音最长 30 秒。应用会先调用 `glm-asr-2512` 转写，再调用 `glm-5.1-highspeed` 根据当前正文文风润色，最后插入光标位置。

生产发布前，建议把智谱请求放到自己的服务端，避免客户端直接持有长期 API Key。
