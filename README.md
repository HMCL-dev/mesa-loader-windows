# Java Mesa Loader for Windows

A Java agent that enables Java applications to load Mesa drivers to replace the default OpenGL implementation.

Usgae:

```bash
java -javaagent:mesa-loader-windows.jar=<driver name> -jar your-application.jar ...
```

Supported drivers:

* `llvmpipe`
* `d3d12`
* `zink`

Requires Windows 10 (or later), Java 6 (or later).

Based on Mesa builds provided by [mmozeiko/build-mesa](https://github.com/mmozeiko/build-mesa).

## License

This project is licensed under the [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) license.

