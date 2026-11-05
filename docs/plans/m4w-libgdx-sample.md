# M4w：libGDX 消费示例与 M3 门控

## 目标

两件事，都是“把结论变成可检验的东西”：

1. 核心库能被一个只看得见公共 API 的下游消费，并把区块变成可渲染的数据；
2. Android 的运行时验证从“待办”变成一份有判据、有解除条件的门控文档。

## 边界

架构文档说过渲染实现位于外部客户端、本仓库不引入 libGDX——这条不因为示例而改变：

- 示例是独立模块 `samples:libgdx-viewer`，**不发布**、不被任何模块依赖，可以整体删掉；
- 核心的 `verifyBytecode` / `verifyModuleBoundaries` 不变：核心仍然不依赖 libGDX；
- 示例只做到“产出可上传到 GL 的数组”，着色器、相机、材质、区块流式加载仍在外部仓库。

## 设计

| 位置 | 职责 |
| --- | --- |
| `samples/libgdx-viewer/build.gradle.kts` | `application` + 共享约定；`runtimeOnly` 引入 natives |
| `viewer/SolidQuery.kt` | 最小输入：`isSolid(x, y, z)`；`MeshData` 值对象与体积统计 |
| `viewer/ChunkMeshBuilder.kt` | 六面剔除 + 顶点/法线/索引生成，绕序与法线一致 |
| `viewer/BlockSolidity.kt` | `ChunkColumn` → `SolidQuery` 的薄适配（世界坐标换算 + air 判定） |
| `viewer/DemoTerrain.kt` | 示例地形；示例与测试共用同一份，面数才对得上 |
| `viewer/ViewerApplication.kt` | libGDX `ApplicationAdapter`，在 `create()` 里构建并打印 |
| `viewer/Main.kt` | `HeadlessApplication` 入口，跑一帧即退出 |

四个决定：

1. **`MeshData` 而不是 libGDX `Mesh`。** `Mesh` 需要 GL 上下文，创建它就得先有窗口；
   数组可以在任何线程产出、在任何后端（含 CI 的 headless）断言，上屏时再
   `mesh.setVertices(positions)`。
2. **`SolidQuery` 而不是 `ChunkColumn`。** 下游可能把区块缓存、服务端方块变更与自己的
   预测地形拼在同一份网格里；绑死区块类型就没法拼。示例另给了 `blockSolidity` 适配器。
3. **越界语义显式写在适配器上。** 单列网格化看不到相邻列，把侧面与下方当成实心才不会在
   列与列之间画出重叠内壁；上方当成空气，顶面照画。这个约定直接决定面数，所以示例与测试
   共用 `demoTerrain`，而不是各写一份。
4. **headless 仍然要 natives。** `gdx-platform:natives-desktop` 必须作为 `runtimeOnly` 加入，
   否则 `GdxNativesLoader` 抛 `SharedLibraryLoadRuntimeException`。
   “不创建 GL 上下文”不等于“不加载本地库”。

## 测试门控

- `ChunkMeshBuilderTest`：单格 6 面 / 24 顶点 / 36 索引；3×3×3 实心 54 面（内部面被剔除）；
  全空 0 面；六个方向法线各出现一次且为单位轴向向量；顶点坐标只会是 0 或 1；
  下标不越界；尺寸为 0 时拒绝构建；示例地形 272 面（252 地面 + 20 柱子）。
- `verifyModuleBoundaries`：示例的编译类路径不含 Netty、协议库与 Minestom。
- `verifyBytecode`：示例同样是 major 65（共享约定），不必为示例放宽。

## 实现时发现的事实

- libGDX 1.13.1 的 `gdx-backend-headless` 在 Windows 上会加载 `gdx64.dll`；不声明
  `gdx-platform:natives-desktop` 时直接失败在 `GdxNativesLoader.load`。
- JDK 25 会打印 `System::load` 的 restricted method 警告（`gdx-jnigen-loader` 引起），
  不影响运行；将来若用 `--illegal-native-access=deny`，需要显式放行。
- `ChunkColumn` 的构造器是 `internal`，示例模块无法自己造一列做单测。因此
  `blockSolidity` 只做坐标换算与 air 判定，正确性由核心层的 `blockStateAt` 用例覆盖，
  示例不再重复造夹具——这是刻意的，不是遗漏。
- 示例最初把越界当成空气，结果地面底面也被画了出来（528 面）。面数对账暴露了这个语义
  问题：越界约定必须与适配器一致，否则每列都会多出一层朝向网格外的面。
