# RailDriver gateway

Interface with the [RailDriver controller](https://raildriver.com/), sending its settings as 20-byte binary UDP frames to a local simulator.

- **Original VB.NET gateway:** open `BRaVE_RailDriver.sln` on Windows. The project in `BRaVE_RailDriver/` is retained unchanged as the fallback.
- **Java alternative:** see [RailDriverJava/README.md](RailDriverJava/README.md) for Java 17+ build/run instructions, ports and native Windows packaging. Software tests and initial macOS hardware checks pass; [actual-controller, simulator, Windows and rights acceptance remain pending](RailDriverJava/VERIFICATION.md).

Run only one gateway at a time. Stop Java before returning to VB.NET, and stop VB.NET before starting Java. The simulator protocol is unchanged.
