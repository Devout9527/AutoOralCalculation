# AutoOralCalculation
一款为小猿口算（小猿AI）量身定制的 Xposed 模块

## 原作者 / 版权声明
本项目基于 **TinyHai** 的 [AutoOralCalculation](https://github.com/TinyHai/AutoOralCalculation) 二次开发适配而来。

- 原始仓库：`https://github.com/TinyHai/AutoOralCalculation`
- 原作者：**TinyHai**
- 前期核心功能（识别/练习/PK/刷分等）的实现与版权均归属原仓库作者 TinyHai，在此深表感谢。

> 本仓库为适配 **小猿AI / 小猿口算 3.140.1** 的 fork：
> 新增了 **H5 练习自动答题、练习页触发自动上分、设置入口适配、识别/自动练习 hook 重构** 等改动。

## 功能
- 只测试了 3.140.1, 其他版本请自行测试
- 口算练习自动答题
- 口算练习刷分（可自定义次数）
- 识别结果永远为正确答案
- 口算 PK 自动答题
- 自定义答题脚本功能（有前端开发经验可自定义答题逻辑）