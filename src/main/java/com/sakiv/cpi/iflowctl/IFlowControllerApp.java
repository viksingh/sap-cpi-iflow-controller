package com.sakiv.cpi.iflowctl;

import com.sakiv.cpi.iflowctl.config.CpiConfiguration;
import com.sakiv.cpi.iflowctl.model.IFlowTarget;
import com.sakiv.cpi.iflowctl.service.CpiHttpClient;
import com.sakiv.cpi.iflowctl.service.IFlowLifecycleService;
import com.sakiv.cpi.iflowctl.util.CsvIFlowReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

// @author Vikas Singh
// Created: 2026-06-21
// Updated: 2026-06-27 - added result summary table
public class IFlowControllerApp {

    private static final Logger log = LoggerFactory.getLogger(IFlowControllerApp.class);

    private enum Mode { STATUS, UNDEPLOY, DEPLOY }

    public static void main(String[] args) {
        log.info("========================================");
        log.info("SAP CPI iFlow Controller v1.1.0");
        log.info("========================================");

        if (args.length < 3) {
            printUsage();
            System.exit(1);
            return;
        }

        String configFile = args[0];
        String csvFile = args[1];
        Mode mode = parseMode(args[2]);
        if (mode == null) {
            System.out.println("Unknown mode: " + args[2]);
            printUsage();
            System.exit(1);
            return;
        }

        boolean sync = false;
        for (int i = 3; i < args.length; i++) {
            if (isSyncFlag(args[i])) {
                sync = true;
            }
        }

        try {
            CpiConfiguration config = new CpiConfiguration(configFile);
            config.validate();
            log.info("Config source: {}", config.getConfigSource());
            log.info("Tenant: {}", config.getBaseUrl());
            log.info("Auth Type: {}", config.getAuthType());

            List<IFlowTarget> targets = new CsvIFlowReader().read(csvFile);
            if (targets.isEmpty()) {
                log.warn("No iFlows found in CSV, nothing to do.");
                return;
            }

            log.info("Mode: {} | iFlows: {} | Sync: {}", mode, targets.size(), sync);

            int ok = 0;
            int failed = 0;
            List<Row> rows = new ArrayList<>();
            try (CpiHttpClient httpClient = new CpiHttpClient(config)) {
                IFlowLifecycleService service = new IFlowLifecycleService(config, httpClient);

                for (IFlowTarget target : targets) {
                    String pkg = target.packageName().isEmpty() ? "-" : target.packageName();
                    String iflow = target.iflowId();
                    try {
                        String iflowId = service.resolveIflowId(target.packageName(), target.iflowId());
                        if (iflowId == null) {
                            failed++;
                            String detail = service.packageResolved(target.packageName())
                                    ? "iFlow not found in package"
                                    : "package not found in tenant";
                            rows.add(new Row("NOT_FOUND", pkg, iflow, detail));
                            log.error("iFlow '{}' not resolved: {} (package '{}')",
                                    target.iflowId(), detail, target.packageName());
                            continue;
                        }
                        boolean success = true;
                        switch (mode) {
                            case STATUS -> {
                                var info = service.getRuntimeInfo(iflowId);
                                rows.add(new Row(info.status(), pkg, iflow, "",
                                        info.deployedOn(), info.deployedBy()));
                            }
                            case UNDEPLOY -> {
                                service.undeploy(iflowId);
                                if (sync) {
                                    String s = service.awaitUndeployed(iflowId);
                                    success = IFlowLifecycleService.NOT_DEPLOYED.equals(s);
                                    rows.add(new Row(success ? "UNDEPLOYED" : s, pkg, iflow,
                                            success ? "" : "timed out waiting for undeploy"));
                                } else {
                                    rows.add(new Row("UNDEPLOYED", pkg, iflow, ""));
                                }
                            }
                            case DEPLOY -> {
                                service.deploy(iflowId);
                                if (sync) {
                                    String s = service.awaitDeployed(iflowId);
                                    success = IFlowLifecycleService.STARTED.equals(s);
                                    String detail;
                                    if (IFlowLifecycleService.ERROR.equals(s)) {
                                        String info = service.getErrorInformation(iflowId);
                                        detail = info != null ? summarize(info) : "deploy reported ERROR";
                                    } else {
                                        detail = success ? "" : "timed out waiting for deploy";
                                    }
                                    rows.add(new Row(s, pkg, iflow, detail));
                                } else {
                                    rows.add(new Row("DEPLOYING", pkg, iflow, ""));
                                }
                            }
                        }
                        if (success) {
                            ok++;
                        } else {
                            failed++;
                        }
                    } catch (Exception e) {
                        failed++;
                        rows.add(new Row("FAILED", pkg, iflow, summarize(e.getMessage())));
                        log.error("Operation failed for iFlow '{}': {}", target.iflowId(), e.getMessage());
                    }
                }
            }

            printSummary(rows, mode, ok, failed);
            log.info("Done. Success: {}, Failed: {}", ok, failed);
            if (failed > 0) {
                System.exit(2);
            }

        } catch (IllegalStateException e) {
            log.error("Configuration Error: {}", e.getMessage());
            printUsage();
            System.exit(1);
        } catch (Exception e) {
            log.error("Run failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static Mode parseMode(String arg) {
        String m = arg.toLowerCase().replaceFirst("^-+", "");
        return switch (m) {
            case "status" -> Mode.STATUS;
            case "undeploy" -> Mode.UNDEPLOY;
            case "deploy", "awaken" -> Mode.DEPLOY;
            default -> null;
        };
    }

    private static boolean isSyncFlag(String arg) {
        String a = arg.toLowerCase().replaceFirst("^-+", "");
        return a.equals("sync") || a.equals("wait");
    }

    private static void printUsage() {
        System.out.println("""

            USAGE:
              java -jar cpi-iflow-controller.jar <config-file> <iflow-csv> <mode> [-sync]

            MODES:
              -status     Show the runtime deployment status of each iFlow
              -deploy     Deploy (start) each iFlow on the runtime
              -undeploy   Stop and undeploy each iFlow from the runtime

            OPTIONS:
              -sync       Wait for each deploy/undeploy to reach a terminal state
                          before moving to the next iFlow (poll interval and timeout
                          are configurable via deploy.poll.interval.ms /
                          deploy.wait.timeout.ms). Default: async (fire and continue).

            ARGUMENTS:
              <config-file>  Connection/credentials properties file (see config.properties.template)
              <iflow-csv>    CSV of iFlows to act on: two columns -> package name, iFlow name

            EXAMPLES:
              java -jar cpi-iflow-controller.jar config.properties iflows.csv -status
              java -jar cpi-iflow-controller.jar config.properties iflows.csv -deploy
              java -jar cpi-iflow-controller.jar config.properties iflows.csv -deploy -sync
              java -jar cpi-iflow-controller.jar config.properties iflows.csv -undeploy -sync
            """);
    }

    private record Row(String result, String pkg, String iflow, String detail,
                       String deployedOn, String deployedBy) {
        Row(String result, String pkg, String iflow, String detail) {
            this(result, pkg, iflow, detail, "", "");
        }
    }

    private static String summarize(String msg) {
        if (msg == null) {
            return "";
        }
        String oneLine = msg.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 60 ? oneLine.substring(0, 57) + "..." : oneLine;
    }

    private static void printSummary(List<Row> rows, Mode mode, int ok, int failed) {
        boolean statusMode = mode == Mode.STATUS;
        boolean hasDetail = rows.stream().anyMatch(r -> !r.detail().isEmpty());
        boolean hasDeployInfo = statusMode && rows.stream()
                .anyMatch(r -> !r.deployedOn().isEmpty() || !r.deployedBy().isEmpty());

        List<String> headers = new ArrayList<>();
        headers.add(statusMode ? "STATUS" : "RESULT");
        headers.add("PACKAGE");
        headers.add("IFLOW");
        if (hasDeployInfo) {
            headers.add("DEPLOYED ON");
            headers.add("DEPLOYED BY");
        }
        if (hasDetail) {
            headers.add("DETAIL");
        }

        List<String[]> table = new ArrayList<>();
        for (Row r : rows) {
            List<String> cells = new ArrayList<>();
            cells.add(r.result());
            cells.add(r.pkg());
            cells.add(r.iflow());
            if (hasDeployInfo) {
                cells.add(r.deployedOn());
                cells.add(r.deployedBy());
            }
            if (hasDetail) {
                cells.add(r.detail());
            }
            table.add(cells.toArray(new String[0]));
        }

        int cols = headers.size();
        int[] w = new int[cols];
        for (int i = 0; i < cols; i++) {
            w[i] = headers.get(i).length();
        }
        for (String[] row : table) {
            for (int i = 0; i < cols; i++) {
                w[i] = Math.max(w[i], row[i].length());
            }
        }

        StringBuilder sep = new StringBuilder("+");
        for (int i = 0; i < cols; i++) {
            sep.append("-".repeat(w[i] + 2)).append("+");
        }

        System.out.println();
        System.out.println(sep);
        printTableRow(headers.toArray(new String[0]), w, cols);
        System.out.println(sep);
        for (String[] row : table) {
            printTableRow(row, w, cols);
        }
        System.out.println(sep);
        System.out.printf("Summary: %d succeeded, %d failed (%d total)%n", ok, failed, rows.size());
    }

    private static void printTableRow(String[] cells, int[] w, int cols) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < cols; i++) {
            sb.append(' ').append(pad(cells[i], w[i])).append(" |");
        }
        System.out.println(sb);
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
