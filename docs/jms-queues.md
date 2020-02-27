# JMS Queues and the Container Service

In the interest of offloading work from the XNAT tomcat server and onto "shadow" servers, we've added two queues, staging and finalizing, to the container service. The former handles command resolution and container launching; the latter handles the uploading of files back to XNAT upon container completion.

## Broker
See `WEB-INF/conf/mq-context.xml` for the XNAT default MQ configuration. You may override these properties in `xnat-conf.properties`.

## Consumer concurrency
Concurrency settings are dynamic and can be adjusted by the site admin from the `Plugin Settings > Container Service > JMS Queue` panel.