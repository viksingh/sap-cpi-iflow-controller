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
```

Arguments: `<config-file> <iflows-csv> <-status|-deploy|-undeploy>`.

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
