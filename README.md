# renga-projects
Renga Projects Service

Documentation: https://renga.readthedocs.io/en/latest/developer/projects_service.html

## Development
Building is done using [sbt](http://www.scala-sbt.org/).

To create a docker image:
```bash
sbt docker:publishLocal
[...]
[info] Successfully tagged renga-projects:<version>
[info] Built image renga-projects:<version>
```

Image name and tag can be manipulated with sbt settings, see
[sbt-native-packager](https://sbt-native-packager.readthedocs.io/en/v1.2.2/formats/docker.html).
