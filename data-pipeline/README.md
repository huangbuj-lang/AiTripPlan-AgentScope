# data-pipeline（独立数据管线，不碰核心链路）

## 启动与运行顺序

```bash
# ① 数据采集（高德Web API→4500条落库，约5分钟，幂等可重跑）
python3 data-pipeline/collect.py            # 全量20城
python3 data-pipeline/collect.py 成都 重庆  # 指定城市

# ② 预处理（清洗打标→特征表）
python3 data-pipeline/preprocess.py

# ③ 动态定价 + 客流预测
python3 data-pipeline/pricing.py
python3 data-pipeline/forecast.py

# ④ 反馈闭环：Java接口 /api/feedback（已在主管服务内，无需单独启动）

# ⑤ 智能客服（独立服务，端口8090）★必须用 python（3.11），python3(3.14)会因pydantic不兼容崩溃
python data-pipeline/chat_server.py

# ⑥ 用户画像
python3 data-pipeline/profile.py
```

## 坑
- 客服服务**只能用 `python` 启动**（3.11）；`python3` 是 3.14，pydantic_core 二进制不兼容 → ModuleNotFoundError
- 客服页 http://localhost:9091/chat.html 依赖 8090 服务存活
- ③⑥ 输入含 trip_plans.city 历史脏值（如"西安七日自行车"），如实标注，未改现有数据
