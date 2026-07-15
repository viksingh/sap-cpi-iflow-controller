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

### `-status`

Shows the runtime deployment status of each iFlow. For deployed artifacts the summary also includes **DEPLOYED ON** (deployment timestamp, from CPI's `DeployedOn`, normalised to local `yyyy-MM-dd HH:mm:ss` whether the tenant returns it as ISO-8601 or the Edm `/Date(ms)/` format) and **DEPLOYED BY** (from `DeployedBy` — a user email for deployments done in the Web UI, or the OAuth client id for deployments done via the API). These two columns appear only when at least one iFlow is deployed.

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

Two columns: package and iFlow. Header row optional.

Each column accepts either the technical id or the display name:

- **package** — the package id (OData key) is tried first; if that lookup fails, the tool falls back to matching the value against the display names of all packages (case-insensitive) and retries with the resolved id.
- **iFlow** — resolved to its id by matching against both the ids and display names of the iFlows in the package.

Quote any value that contains commas or spaces.

```csv
package,iflow
OrderToCash,SalesOrder_ECC_to_S4HANA
OrderToCash,Delivery_S4_to_EWM
"SAP ERP and SAP S/4HANA Integration with SAP Ariba","SOAP Inbound Pass Through Content"
```

## Example output

```text
+--------------+----------------------+--------------------------------+---------------------+-------------+
| STATUS       | PACKAGE              | IFLOW                          | DEPLOYED ON         | DEPLOYED BY |
+--------------+----------------------+--------------------------------+---------------------+-------------+
| STARTED      | OrderToCash          | SalesOrder_ECC_to_S4HANA       | 2026-07-14 09:12:03 | vikas       |
| ERROR        | OrderToCash          | Delivery_S4_to_EWM             | 2026-07-14 09:12:41 | vikas       |
| STARTED      | FinanceIntegration   | Invoice_IDoc_to_S4             | 2026-07-13 18:02:55 | admin       |
+--------------+----------------------+--------------------------------+---------------------+-------------+
Summary: 3 succeeded, 0 failed (3 total)
```
