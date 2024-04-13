## Plugin: "aeonics.http"

This *Nucleus Runtime* plugin provides HTTP server capabilities and defines
high level endpoints.

## Compile and package

You can use your favourite tool (Maven, Gradle,...) but to be honest, we prefer
the plain simple standard and out-of-the-box `javac`.

The `ae.jar` file is the binary distribution of the *aeonics.system* core.

```shell
javac -source 11 -target 11 -nowarn -XDignore.symbol.file \
      -cp ae.jar \
      -d aeonics.http/bin \
      --module-source-path .\
      --module aeonics.http

jar -c --file=aeonics.http.jar \
    -C aeonics.http/bin/aeonics.http \
    .
```

## Deployment

Place the binary distribution in the `plugins` folder of your installation.
