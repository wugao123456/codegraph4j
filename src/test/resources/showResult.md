# CodeGraph Explore Result

## Metadata

| Field | Value |
|-------|-------|
| **Tool** | codegraph_explore |
| **Query** | Consumer refershConfig |
| **Timestamp** | 2026-06-29 14:08:54 |
| **Status** | ✅ Success |

## Content

**Exploration: Consumer refershConfig**

Found 9 symbols across 3 files.

**Blast radius — what depends on these (update/verify before editing)**

- `refershConfig` (ApiInboundController.java:161) — 2 callers in `/Users/wugao-pc/Desktop/Project/stream/src/main/java/com/haizhi/stream/controller/ApiInboundController.java`, `/Users/wugao-pc/Desktop/Project/stream/src/main/java/com/haizhi/stream/controller/ApiInboundController.java`; ⚠ no covering tests found


**Source Code**

> The code below is the **verbatim, current on-disk source** of these files — re-read from disk on this call and line-numbered, byte-for-byte identical to what the Read tool returns.

**StreamTaskFactory.java** — clustered — `/Users/wugao-pc/Desktop/Project/stream/src/main/java/com/haizhi/stream/config/StreamTaskFactory.java`

```java
52	    @Override
53	    public void run(ApplicationArguments args) throws Exception {
54	        try {
55	            log.info("启动 消费类型 ："+streamConfig.getStreamType() +"库"+ streamConfig.getGraphName());
56	            if (StreamType.http.toString().equals(streamConfig.getStreamType())) {
57	                return;
58	            }
59	            taskThreadPool = new TaskThreadPool(streamConfig.getCorePoolSize(), streamConfig.getQueueCapacity());
60	            SchemaMapPo schemaMapPo = findMaxVersionByGraphLimit(streamConfig.getGraphName());
61	            if (schemaMapPo == null) {
62	                log.warn("schemaMap:" + streamConfig.getGraphName() + " not exist;");
63	                return;
64	            }
65	            SchemaMap mapp = JSONUtils.parseObject(schemaMapPo.getSchemaContent(), SchemaMap.class);
66	            ValidatorResult result = checkSchemaMapp(mapp);
67	            if (!result.isSuccess()) {
68	                StringBuilder errorMsg = new StringBuilder();
69	                result.getAllErrorMsg().forEach(errorMsg::append);
70	                log.warn(errorMsg.toString());
71	                return;
72	            }
73	            createConsumer(mapp);
74	        } catch (Exception e) {
75	            log.error("init error:", e);
76	            throw e;
77	        }
78	    }
79	
80	    public Response refershConfig(SchemaMap mapp) {
81	        if (mapp == null || mapp.getSchemaConfigs() == null) {
82	            return Response.error("配置不能为空");
83	        }
84	        ValidatorResult result = checkSchemaMapp(mapp);
85	        if (!result.isSuccess()) {
86	            StringBuilder errorMsg = new StringBuilder();
87	            result.getAllErrorMsg().forEach(errorMsg::append);
88	            log.warn("refershConfig error" + errorMsg.toString());
89	            return Response.error(errorMsg.toString());
90	        }
91	        createConsumer(mapp);
92	        return Response.success();
93	
94	    }
95	
96	    private void createConsumer(SchemaMap mapp) {
97	        if (consumer != null) {
98	            consumer.stopConsuming();
99	        }
100	        List<String> topics = mapp.getSchemaConfigs().stream().map(schemaConfig ->
101	                schemaConfig.getTopic()).collect(Collectors.toList());
102	        if (StreamType.kafka.toString().equals(streamConfig.getStreamType())) {
103	            String message = StreamAdminClient.isTopicExists(topics, streamConfig);
104	            if (!StringUtils.isEmpty(message)) {
105	                log.error( message);
106	                throw new RuntimeException(message);
107	            }
108	            consumer = new KafkaConsumer(mapp, streamConfig);
109	        }
110	        Callback callback = (Callback<TaskContext, List<String>>) (taskContext, batchLine) -> {
111	            TaskJob taskJob = new TaskJob(recordServer, fileHandler,
112	                    taskContext, batchLine);
113	            taskThreadPool.submitTask(taskJob);
114	        };
115	        consumer.startConsuming(callback);
116	        log.info("初始化消费者成功，准备消费");
117	    }
118	
119	    public ValidatorResult checkSchemaMapp(SchemaMap mapp) {
120	        ValidatorResult result = new ValidatorResult(10);
121	        result.setSuccess(true);
122	        Domain domain = dcMetadataBaseCache.getDomain(streamConfig.graphName);
123	        if (domain == null) {
124	            result.addErrorMsg("domain:" + streamConfig.graphName + " not exist;");
125	            result.setSuccess(false);
126	            return result;
127	        }
128	        mapp.getSchemaConfigs().forEach(map -> {
129	            if (map.getVertices() != null) {
130	                for (Map.Entry<String, List<PropertyOo>> entry : map.getVertices().entrySet()) {
131	                    Schema schema = domain.getSchema(entry.getKey());
132	                    if (schema == null) {
133	                        result.addErrorMsg("清检查 vertex:" + entry.getKey() + " not exist;");
134	                        result.setSuccess(false);
135	                        continue;
136	                    }
137	                    if (entry.getValue() == null || checkMain(entry.getValue(), schema))
138	                        if (schema == null) {
139	                            result.addErrorMsg("请检查 vertex:" + entry.getKey() + " 主键未配置;");
140	                            result.setSuccess(false);
141	                            continue;
142	                        }
143	                    entry.getValue().forEach(property -> {
144	                        if (property != null && !schema.getFieldMap().containsKey(property.getDstField())) {
145	                            result.setSuccess(false);
146	                            result.addErrorMsg("vertex:" + entry.getKey() + " dstField:" + property.getDstField() + " 在目标表中不存在;");
147	                        }
148	                    });
149	
150	                }
151	            }
152	            if (map.getEdges() != null) {
153	                for (Map.Entry<String, List<PropertyOo>> entry : map.getVertices().entrySet()) {
154	                    Schema schema = domain.getSchema(entry.getKey());
155	                    if (schema == null) {
156	                        result.addErrorMsg("Edge:" + entry.getKey() + " not exist;");
157	                        result.setSuccess(false);
158	                        continue;
159	                    }
160	                    if (entry.getValue() == null || checkMain(entry.getValue(), schema))
161	                        if (schema == null) {
162	                            result.addErrorMsg("Edge:" + entry.getKey() + " 主键未配置;");
163	                            result.setSuccess(false);
164	                            continue;
165	                        }
166	                    if (entry.getValue() != null) {
167	                        entry.getValue().forEach(property -> {
168	                            if (property != null && !schema.getFieldMap().containsKey(property.getDstField())) {
169	                                result.setSuccess(false);
170	                                result.addErrorMsg("Edge:" + entry.getKey() + " dstField:" + property.getDstField() + "  在目标表中不存在;");
171	                            }
172	                        });
173	                    }
174	
175	                }
176	            }
177	
178	        });
179	        return result;
180	    }

```

**Consumer.java** — clustered — `/Users/wugao-pc/Desktop/Project/stream/src/main/java/com/haizhi/stream/config/Consumer.java`

```java
31	    public Consumer(SchemaMap schemaMap, StreamConfig streamConfig) {
32	        this.schemaMap = schemaMap;
33	        this.streamConfig = streamConfig;
34	        this.executorService = Executors.newSingleThreadExecutor();
35	        topics=new ArrayList<>();
36	        taskCacheContext = new ConcurrentHashMap<>();
37	        schemaMap.getSchemaConfigs().forEach(schemaConfig -> {
38	            getTaskContext(schemaConfig.getTopic());
39	        });
40	    }

```

**ApiInboundController.java** — clustered — `/Users/wugao-pc/Desktop/Project/stream/src/main/java/com/haizhi/stream/controller/ApiInboundController.java`

```java
83	    @Operation(summary = "手动刷新配置，并通知其他服务同步刷新")
84	    @PostMapping(path = "manualConfig", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
85	    public Response config(@RequestBody SchemaMap mapp, HttpServletRequest request) {
86	        if (StreamType.http.toString().equals(streamConfig.getStreamType())) {
87	            Response response = Response.error("http 接入方式不用配置");
88	            response.setSuccess(false);
89	            return response;
90	        }
91	        if (mapp == null || mapp.getSchemaConfigs() == null) {
92	            return Response.error("配置不能为空");
93	        }
94	        if (isRefreshing.get()) {
95	            return Response.success("正在刷新配置中、请稍后再试");
96	        }
97	        Response response;
98	        isRefreshing.set(true);
99	        try {
100	            SchemaMapPo schemaMapPo = taskContextFactory.findMaxVersionByGraphLimit(streamConfig.getGraphName());
101	            response = taskContextFactory.refershConfig(mapp);
102	            if (response.isSuccess()) {
103	                SchemaMapPo newSchemaMapPo = new SchemaMapPo();
104	                newSchemaMapPo.setGraph(streamConfig.getGraphName());
105	                newSchemaMapPo.setSchemaContent(JSONUtils.toJSONString(mapp));
106	                newSchemaMapPo.setVersion(schemaMapPo == null ? 0 : schemaMapPo.getVersion() + 1);
107	                // schemaMapDao.save(newSchemaMapPo);
108	                schemaMapDao.saveAndFlush(newSchemaMapPo);
109	            }
110	//         获取当前服务信息
111	            NamingService namingService = nacosServiceManager.getNamingService(
112	                    nacosDiscoveryProperties.getNacosProperties());
113	            List<Instance> instances = namingService.getAllInstances(nacosDiscoveryProperties.getService());
114	
115	            // 获取原始请求的Cookie
116	            String originalCookie = request.getHeader("Cookie");
117	
118	            // 使用RestTemplateBuilder创建带拦截器的RestTemplate
119	            RestTemplate restTemplate = new RestTemplate();
120	            for (Instance instance : instances) {
121	                String url = "http://" + instance.getIp() + ":" + instance.getPort() + contextPath + "/bulk/refershConfig";
122	                // 创建请求头
123	                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
124	                if (originalCookie != null) {
125	                    headers.add("Cookie", originalCookie);
126	                }
127	                // 创建HttpEntity
128	                org.springframework.http.HttpEntity<?> entity = new org.springframework.http.HttpEntity<>(headers);
129	                // 发送请求时带上Cookie
130	                ResponseEntity<Response> responseGet = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, Response.class);
131	                if (responseGet == null || !responseGet.getStatusCode().is2xxSuccessful()) {
132	                    log.error("刷新配置失败 instanceId:{},Message:{}", instance.getInstanceId(), responseGet != null ? responseGet.getBody() : "Response is null");
133	                    isRefreshing.set(false);
134	                    return Response.error("刷新配置失败 instanceId:" + instance.getInstanceId() +
135	                            (responseGet != null ? "Message:" + responseGet.getBody().toString() : "Response is null"));
136	                }
137	                if (responseGet.getBody() != null && !responseGet.getBody().isSuccess()) {
138	                    log.error("刷新配置失败 instanceId:{},Message:{}", instance.getInstanceId(), responseGet.getBody());
139	                    isRefreshing.set(false);
140	                    return Response.error("刷新配置失败 instanceId:" + instance.getInstanceId() + "Message:" + responseGet.getBody().toString());
141	                }
142	                log.info("刷新配置成功 instanceId:{}", instance.getInstanceId());
143	            }
144	        } catch (Exception e) {
145	            log.error("配置刷新失败", e);
146	            isRefreshing.set(false);
147	            return Response.error("配置刷新失败");
148	        }
149	        isRefreshing.set(false);
150	        return response;
151	    }

```

```java
161	    @Operation(summary = "前台刷新kafka/datahub接入方式，自动配置模版")
162	    @GetMapping(path = "refershConfig")
163	    public Response refershConfig() {
164	        if (isRefreshing.get()) {
165	            return Response.success("正在刷新配置中");
166	        }
167	        isRefreshing.set(true);
168	        Response response;
169	        try {
170	            SchemaMapPo schemaMapPo = taskContextFactory.findMaxVersionByGraphLimit(streamConfig.getGraphName());
171	            if (schemaMapPo == null) {
172	                return Response.error("未找到配置");
173	            }
174	            SchemaMap mapp = JSONUtils.parseObject(schemaMapPo.getSchemaContent(), SchemaMap.class);
175	            response = taskContextFactory.refershConfig(mapp);
176	        } catch (Exception e) {
177	            log.error("refresh error:", e);
178	            isRefreshing.set(false);
179	            return Response.error("刷新失败");
180	        }
181	        isRefreshing.set(false);
182	        return response;
183	    }

```


---

## Stats

- Content items: 1
- Total characters: 13188
