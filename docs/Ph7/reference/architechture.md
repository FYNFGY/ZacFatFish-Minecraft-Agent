## MCP和Bridge模组架构调整
注：此文档任务的开发将服务于最终的服务端模组开发
#### MCP Subagent子代理协作架构
##### 组成DAG
1. Scheduler (Main) 缩写为SR
2. Chatter (Sub) 缩写为CR
3. Affecter (Sub) 缩写为AR
4. Environment Sensor for Scheduler (Sub) 缩写为ESRS
5. Environment Sensor for Affecter (Subsub) 缩写为ESRA
##### 作用
###### SR
SR是主Agent，需要组织每个Subagent的工作和决策每个任务
1. SR能主动地调用MCP里面的所有工具
2. SR能够及时地中断ER和AR的工作，为ER和AR及时创建新的任务
3. SR和CR之间应该为两个Actors模型，SR和CR之间应该有反复的信息交换
4. SR不得等待除了CR外任何一个SubAgent完成任务，空闲时向CR发布任务请求，再由CR把任务总结并下达给SR
5. SR应该手持一份任务清单(TodoList)
SR的核心角色
1. SR通过分析ESRS返回的消息确定玩家的状态和周围的环境(scan+surface)。玩家的状态应该包括:玩家扣血，饱食度下降，在水中气泡条下降，当前位置。
2. ESRS向SR报告目前的环境摘要，SR分析后指挥SubAgent下一步动作。
3. SR向CR反馈CR所需要的一切信息。
4. SR拥有一个Memory记忆，用来存储所需的独立于上下文的信息(比如某些关键特性的坐标)。下一个SR在没有本次上下文时，也能够很好地通过Memory接手工作。
###### CR
CR专门用于和玩家聊天
1. CR分析玩家对话，如果玩家向AI玩家发送的消息不需要SR执行(比如日常聊天)，则CR单独处理这些聊天。如果玩家下达了明确的任务或者任务目标变更，CR需要处理这些请求并通知SR。SR将修改TodoList并根据实际情况确定是否中断本次任务的执行还是执行下次任务。
2. CR向SR提出请求，SR给出回答，CR总结后向玩家方块
3. CR也持有一个Memory记忆，用来存储聊天的关键上下文。
###### AR
AR用于执行各种动作，比如放置船，行走，开箱子等等。
1. AR由SR开启并调用各种MCP Tools完成任务
2. AR可以通过ESRA的信息自行修正自己的行为(比如ESRA检测到在AR执行走路逻辑时，3s内xz坐标变化量小于1应该及时报告给AR，并包环境报告给AR，AR决策(比如绕路)或选择报告SR)
###### ESRS
ESRS是SR的环境感受器，ESRS与SR协作举例：
1. 当ESRS检测到玩家气泡柱减少，应该报告给SR
2. 当ESRS检测到周围有矿石的时候，应该报告给SR
3. 当ESRS检测到玩家受伤时，分析原因并报告给SR
4. AR可以向ESRS索要环境信息
###### ESRA
ESRA是AR的环境感受器，AR和ESRA协作示例:
1. ESRA检测到玩家被卡住(在搭高的时候y坐标几乎不变，在行走时xz几乎不变，在向下挖掘时z坐标变化异常(可以引入看门狗))整合环境信息并及时报告给AR
2. AR通过ESRA和SR的信息选择是否挖掘/破花还是绕路
3. AR发现当前任务阻塞或难以完成是时报告给SR，行走逻辑阻塞响应(看门狗)一律设置为3s

Remark:父子Agent之间不可以相互等待，SR等待CR除外。

#### Bridge Mod (Name: ZacFatFish Bridge Mod)
1. Bridge可以支持各种模组来支持整合包。
2. Bridge模组也应该支持没有其他模组的NewForge存档
3. Memory和TodoList应当存储在服务端，并启用标识符，方便服务器两个AI的活动
4. Bridgemod在客户端和服务端应该为同一个模组
5. 着重注意服务端与客户端通信
6. 所有的看门狗应该由Bridge模组提供
7. Baritone模组应该成为本模组的前置
8. 配置文件: zff_bridge.config 可以配置看门狗的响应时间
9. 管理员可以使用指令'\zff stop'终止AI玩家所有的工作并反馈给MCP客户端的SR
10. 服务端不需要额外开放端口也可以使用Bridge模组
