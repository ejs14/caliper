/*
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.caliper;

import com.google.caliper.UserException.ExceptionFromUserCodeException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ObjectArrays;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.UUID;

/**
 * Creates, executes and reports benchmark runs.
 */
public final class Runner {

  /** Command line arguments to the process */
  private Arguments arguments;
  private ScenarioSelection scenarioSelection;

  /**
   * Returns the UUID of the executing host. Multiple runs by the same user on
   * the same machine should yield the same result.
   */
  private String getExecutedByUuid() {
    try {
      File dotCaliperRc = new File(System.getProperty("user.home"), ".caliperrc");
      Properties properties = new Properties();
      if (dotCaliperRc.exists()) {
        properties.load(new FileInputStream(dotCaliperRc));
      }

      String userUuid = properties.getProperty("userUuid");
      if (userUuid == null) {
        userUuid = UUID.randomUUID().toString();
        properties.setProperty("userUuid", userUuid);
        properties.store(new FileOutputStream(dotCaliperRc), "");
      }

      return userUuid;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void run(String... args) {
    this.arguments = Arguments.parse(args);
    this.scenarioSelection = new ScenarioSelection(arguments);
    Run run = runOutOfProcess();
    new ConsoleReport(run).displayResults();
    postResults(run);
  }

  private void postResults(Run run) {
    String postHost = arguments.getPostHost();
    if ("none".equals(postHost)) {
      return;
    }

    try {
      URL url = new URL(postHost + run.getExecutedByUuid() + "/" + run.getBenchmarkName());
      HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
      urlConnection.setDoOutput(true);
      Xml.runToXml(run, urlConnection.getOutputStream());
      if (urlConnection.getResponseCode() == 200) {
        System.out.println("");
        System.out.println("View current and previous benchmark results online:");
        System.out.println("  " + url);
        return;
      }

      System.out.println("Posting to " + postHost + " failed: "
          + urlConnection.getResponseMessage());
      BufferedReader reader = new BufferedReader(
          new InputStreamReader(urlConnection.getInputStream()));
      String line;
      while ((line = reader.readLine()) != null) {
        System.out.println(line);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private double executeForked(Scenario scenario) {
    ProcessBuilder builder = new ProcessBuilder();
    List<String> command = builder.command();
    command.addAll(Arrays.asList(scenario.getVariables().get(Scenario.VM_KEY).split("\\s+")));
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(InProcessRunner.class.getName());
    command.add("--warmupMillis");
    command.add(String.valueOf(arguments.getWarmupMillis()));
    command.add("--runMillis");
    command.add(String.valueOf(arguments.getRunMillis()));
    for (Entry<String, String> entry : scenario.getParameters().entrySet()) {
      command.add("-D" + entry.getKey() + "=" + entry.getValue());
    }
    command.add(arguments.getSuiteClassName());

    BufferedReader reader = null;
    try {
      builder.redirectErrorStream(true);
      builder.directory(new File(System.getProperty("user.dir")));
      Process process = builder.start();

      reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
      String firstLine = reader.readLine();
      Double nanosPerTrial = null;
      try {
        nanosPerTrial = Double.valueOf(firstLine);
      } catch (NumberFormatException ignore) {
      }

      String anotherLine = reader.readLine();
      if (nanosPerTrial != null && anotherLine == null) {
        return nanosPerTrial;
      }

      String message = "Failed to execute " + command;
      System.err.println(message);
      System.err.println("  " + firstLine);
      do {
        System.err.println("  " + anotherLine);
      } while ((anotherLine = reader.readLine()) != null);
      throw new ConfigurationException(message);
    } catch (IOException e) {
      throw new ConfigurationException(e);
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  // TODO: check if this is platform-independent
  @SuppressWarnings("HardcodedLineSeparator")
  private static final String RETURN = "\r";

  private Run runOutOfProcess() {
    String executedByUuid = getExecutedByUuid();
    Date executedDate = new Date();
    Builder<Scenario, Double> resultsBuilder = ImmutableMap.builder();

    try {
      List<Scenario> scenarios = scenarioSelection.select();
      int i = 0;
      for (Scenario scenario : scenarios) {
        beforeMeasurement(i++, scenarios.size(), scenario);
        double nanosPerTrial = executeForked(scenario);
        afterMeasurement(nanosPerTrial);
        resultsBuilder.put(scenario, nanosPerTrial);
      }

      // blat out our progress bar
      System.out.print(RETURN);
      for (int j = 0; j < 80; j++) {
        System.out.print(" ");
      }
      System.out.print(RETURN);

      return new Run(resultsBuilder.build(), arguments.getSuiteClassName(), executedByUuid, executedDate);
    } catch (Exception e) {
      throw new ExceptionFromUserCodeException(e);
    }
  }

  private void beforeMeasurement(int index, int total, Scenario scenario) {
    double percentDone = (double) index / total;
    int runStringLength = 63; // so the total line length is 80
    String runString = String.valueOf(scenario);
    if (runString.length() > runStringLength) {
      runString = runString.substring(0, runStringLength);
    }
    System.out.printf(RETURN + "%2.0f%% %-" + runStringLength + "s",
        percentDone * 100, runString);
  }

  private void afterMeasurement(double nanosPerTrial) {
    System.out.printf(" %10.0fns", nanosPerTrial);
  }

  public static void main(String... args) {
    try {
      new Runner().run(args);
    } catch (UserException e) {
      e.display();
      System.exit(1);
    }
  }

  public static void main(Class<? extends Benchmark> suite, String... args) {
    main(ObjectArrays.concat(args, suite.getName()));
  }
}
