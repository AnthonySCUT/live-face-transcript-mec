# Third-party notices

`funasr_wss_server.py` is based on the FunASR Python WebSocket runtime server from [modelscope/FunASR](https://github.com/modelscope/FunASR/blob/main/runtime/python/websocket/funasr_wss_server.py), licensed under Apache-2.0. The local changes select SenseVoiceSmall for multilingual final recognition, expose the offline model as a command-line option, and leave the unused server-side speaker verification model disabled by default.
