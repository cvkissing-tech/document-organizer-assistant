# 参与贡献

欢迎提交问题和改进建议。

## 开发流程

1. 从 `main` 创建独立功能分支。
2. 保持改动聚焦，并为行为变化补充测试。
3. 提交前运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

4. 在 Pull Request 中说明改动目的、验证结果，以及涉及界面时的真机截图。

## 提交问题

请写明 Android 版本、设备型号、操作步骤、预期结果和实际结果。不要上传私人文档、访问令牌或签名文件。

