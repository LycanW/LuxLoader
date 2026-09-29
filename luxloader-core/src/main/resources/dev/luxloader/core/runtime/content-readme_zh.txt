LuxLoader 内容目录
==================

这个目录与 mods/ 同级，放的是「渲染管线」相关的东西，不是模组本体。

pipelines/      把管线插件放进这里。三种放法都行：
                  · foo.jar           单个 jar（发布形态）
                  · foo.zip           同上，zip 与 jar 同构
                  · my-pipeline/      目录形态：里面放一个或多个 jar，
                                      外加自己的着色器源码等文件。
                                      开发期改着色器不用重新打包。

shader-cache/   着色器编译缓存，删掉只会让下次启动慢一点。

reports/        诊断报告。出问题时把最新的那份发出来即可定位。

配置在 config/luxloader/luxloader.json（模组生态惯例，没有挪到这里）。

插件没有生效时按这个顺序查：
  1. jar/zip 里有没有 META-INF/services/dev.luxloader.api.plugin.PipelinePlugin
  2. 日志里搜「发现 N 个插件」与「插件跳过」
  3. 诊断报告里的「已注册管线」段落会写明每条为什么不能用

构建与安装说明请查看 LuxLoader 及所用插件仓库的 README.md。
