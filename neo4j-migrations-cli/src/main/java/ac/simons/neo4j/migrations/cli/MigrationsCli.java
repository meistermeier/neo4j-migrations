/*
 * Copyright 2020-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ac.simons.neo4j.migrations.cli;

import ac.simons.neo4j.migrations.core.Defaults;
import ac.simons.neo4j.migrations.core.MigrationsConfig;
import ac.simons.neo4j.migrations.core.MigrationsConfig.TransactionMode;
import ac.simons.neo4j.migrations.core.MigrationsException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import org.graalvm.nativeimage.ImageInfo;
import org.neo4j.driver.AuthToken;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Logging;

/**
 * Commandline interface to Neo4j migrations.
 *
 * @author Michael J. Simons
 * @since 0.0.5
 */
@Command(
	name = "neo4j-migrations",
	mixinStandardHelpOptions = true,
	description = "Migrates Neo4j databases.",
	subcommands = { InfoCommand.class, MigrateCommand.class },
	versionProvider = ManifestVersionProvider.class
)
public final class MigrationsCli implements Runnable {

	static final Logger LOGGER;

	static {
		configureLogging();
		LOGGER = Logger.getLogger(MigrationsCli.class.getName());
	}

	@SuppressWarnings("squid:S4792")
	private static void configureLogging() {
		try {
			LogManager.getLogManager()
				.readConfiguration(MigrationsCli.class.getResourceAsStream("/logging.properties"));
		} catch (IOException e) {
			throw new MigrationsException("logging.properties are missing. Is your distribution of neo4j-migrations broken?");
		}
	}

	/**
	 * Entry point to the CLI.
	 * @param args The command line arguments
	 */
	public static void main(String... args) {

		int exitCode = new CommandLine(new MigrationsCli()).execute(args);
		System.exit(exitCode);
	}

	@Option(
		names = { "-a", "--address" },
		description = "The address this migration should connect to. The driver supports bolt, bolt+routing or neo4j as schemes.",
		required = true,
		defaultValue = "bolt://localhost:7687"
	)
	private URI address;

	@Option(
		names = { "-u", "--username" },
		description = "The login of the user connecting to the database.",
		required = true,
		defaultValue = Defaults.DEFAULT_USER
	)
	private String user;

	@Option(
		names = { "-p", "--password" },
		description = "The password of the user connecting to the database.",
		required = true,
		arity = "0..1", interactive = true
	)
	private char[] password;

	@Option(
		names = { "--package" },
		description = "Package to scan. Repeat for multiple packages."
	)
	private String[] packagesToScan = new String[0];

	@Option(
		names = { "--location" },
		description = "Location to scan. Repeat for multiple locations."
	)
	private String[] locationsToScan = new String[0];

	@Option(
		names = { "--transaction-mode" },
		description = "The transaction mode to use.",
		defaultValue = Defaults.TRANSACTION_MODE_VALUE
	)
	private TransactionMode transactionMode;

	@Option(
		names = { "-d", "--database" },
		description = "The database that should be migrated (Neo4j 4.0+)."
	)
	private String database;

	@Option(
		names = { "-v" },
		description = "Log the configuration and a couple of other things."
	)
	private boolean verbose;

	@Option(
		names = { "--validate-on-migrate" },
		description = "Validating helps you verify that the migrations applied to the database match the ones available locally and is on by default.",
		defaultValue = Defaults.VALIDATE_ON_MIGRATE_VALUE
	)
	private boolean validateOnMigrate;

	@Option(
		names = { "--autocrlf" },
		description = "Automatically convert Windows line-endings (CRLF) to LF when reading resource based migrations, pretty much what the same Git option does during checkin.",
		defaultValue = Defaults.AUTOCRLF_VALUE
	)
	private boolean autocrlf;

	@Option(
		names = { "--with-max-connection-pool-size" },
		description = "Configure the connection pool size, hardly ever needed to change.",
		defaultValue = "1",
		hidden = true
	)
	private int maxConnectionPoolSize;

	@Spec
	private CommandSpec commandSpec;

	public void run() {
		throw new CommandLine.ParameterException(commandSpec.commandLine(), "Missing required subcommand");
	}

	/**
	 * @return The migrations config based on the required options.
	 */
	MigrationsConfig getConfig() {

		MigrationsConfig config = MigrationsConfig.builder()
			.withLocationsToScan(locationsToScan)
			.withPackagesToScan(packagesToScan)
			.withTransactionMode(transactionMode)
			.withDatabase(database)
			.withValidateOnMigrate(validateOnMigrate)
			.withAutocrlf(autocrlf)
			.build();

		if (ImageInfo.inImageRuntimeCode() && config.getPackagesToScan().length != 0) {
			throw new UnsupportedConfigException(
				"Java based migrations are not supported in native binaries. Please use the Java based distribution.");
		}

		config.logTo(LOGGER, verbose);
		return config;
	}

	Driver openConnection() {

		Config driverConfig = Config.builder()
			.withMaxConnectionPoolSize(maxConnectionPoolSize)
			.withUserAgent("neo4j-migrations")
			.withLogging(Logging.console(Level.SEVERE)).build();
		AuthToken authToken = AuthTokens.basic(user, new String(password));
		Driver driver = GraphDatabase.driver(address, authToken, driverConfig);
		boolean verified = false;
		try {
			driver.verifyConnectivity();
			verified = true;
		} finally {
			// Don't want to rethrow and adding another frame.
			if (!verified) {
				driver.close();
			}
		}
		return driver;
	}
}
