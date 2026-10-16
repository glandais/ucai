
UCAI is a virtual cycling races platform, mostly realistic.

Three components :
 - the kernel : handle races, simulate things, handle packs, routes, timings, ...
 - the frontend : display races of the kernel
 - the controller :
    - retrieve real time race informations
    - give instructions to racers

Kernel server is fast, secured, expose APIs, with SSE.

Frontend is a website displaying Kernel infos. Live races or replays.

Controller can be :
 - an automated system accessing kernel APIs
 - a human giving manual instructions

First spike :
 - three races :
    - Milan-San Remo
    - Liège-Bastogne-Liège
    - Il Lombardia
 - design v1 API
 - kernel v1
 - team v1
    - integrated in kernel code but interacting using HTTP
 - batch race runner (kernel+teams)
 - frontend v1
    - replay race
