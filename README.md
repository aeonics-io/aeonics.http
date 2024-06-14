## Plugin: "aeonics.http"

This *Nucleus Runtime* plugin provides HTTP server capabilities and defines
high level endpoints.

## Compile and package

You can use your favourite tool (Maven, Gradle,...) but to be honest, we prefer
the plain simple standard and out-of-the-box `javac`.

The binary distribution of the *aeonics.boot* jar should be in the
current directory, and the *aeonics.core* jar should be in the `plugins` 
directory.

```shell
javac -source 11 -target 11 -nowarn -XDignore.symbol.file \
      -d aeonics.http/bin \
      --module-path .;./plugins \
      --module-source-path .\
      --module aeonics.http

jar -c --file=aeonics.http.jar \
    -C aeonics.http/bin/aeonics.http \
    .
```

## Deployment

Place the binary distribution in the `plugins` folder of your installation.
