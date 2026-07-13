# SAP CPI iFlow Controller

A small command-line tool to show the runtime status of SAP CPI integration flows, deploy (start) them, or stop (undeploy) them. It reads a CSV list of iFlows and talks to the CPI OData v1 API.

## Build

```bash
mvn clean package
```

Produces `target/cpi-iflow-controller-1.1.0.jar` (Java 21).

## Run

```bash
# show status
java -jar target/cpi-iflow-controller-1.1.0.jar config.properties iflows.csv -status

# deploy (start)
java -jar target/cpi-iflow-controller-1.1.0.jar config.properties iflows.csv -deploy

# stop & undeploy
java -jar target/cpi-iflow-controller-1.1.0.jar config.properties iflows.csv -undeploy

# deploy and wait for each iFlow to finish before moving to the next
java -jar target/cpi-iflow-controller-1.1.0.jar config.properties iflows.csv -deploy -sync
```

Arguments: `<config-file> <iflows-csv> <-status|-deploy|-undeploy> [-sync]`.

### `-sync`

The CPI deploy/undeploy APIs are asynchronous — by default the tool fires the request and immediately moves on (reporting `DEPLOYING`/`UNDEPLOYED`). Add `-sync` to poll runtime status until each iFlow reaches a terminal state before continuing:

- **deploy** waits for `STARTED` (success) or `ERROR` (failure); on `ERROR` the SAP error detail is shown in the summary.
- **undeploy** waits for the artifact to be removed (`NOT_DEPLOYED`).

If an iFlow does not settle within the timeout, it is reported as failed and the run continues. Poll interval and timeout are configurable:

```properties
deploy.poll.interval.ms=5000
deploy.wait.timeout.ms=300000
```

## config.properties

```properties
cpi.base.url=https://your-tenant.it-cpi018.cfapps.eu10.hana.ondemand.com
cpi.auth.type=oauth2
cpi.oauth.token.url=https://your-tenant.authentication.eu10.hana.ondemand.com/oauth/token
cpi.oauth.client.id=your-client-id
cpi.oauth.client.secret=your-client-secret
```

## iflows.csv

Two columns: package name and iFlow name (technical id or display name). Header row optional.

```csv
package,iflow
OrderToCash,SalesOrder_ECC_to_S4HANA
OrderToCash,Delivery_S4_to_EWM
FinanceIntegration,Invoice_IDoc_to_S4
```

## Example output

```text
+--------------+----------------------+--------------------------------+
| STATUS       | PACKAGE              | IFLOW                          |
+--------------+----------------------+--------------------------------+
| STARTED      | OrderToCash          | SalesOrder_ECC_to_S4HANA       |
| ERROR        | OrderToCash          | Delivery_S4_to_EWM             |
| STARTED      | FinanceIntegration   | Invoice_IDoc_to_S4             |
+--------------+----------------------+--------------------------------+
Summary: 3 succeeded, 0 failed (3 total)
```
