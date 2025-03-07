/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.amazon.redshift.jdbc;

import com.amazon.redshift.AuthMech;
import com.amazon.redshift.Driver;
import com.amazon.redshift.RedshiftNotification;
import com.amazon.redshift.RedshiftProperty;
import com.amazon.redshift.copy.CopyManager;
import com.amazon.redshift.core.BaseConnection;
import com.amazon.redshift.core.BaseStatement;
import com.amazon.redshift.core.CachedQuery;
import com.amazon.redshift.core.ConnectionFactory;
import com.amazon.redshift.core.Encoding;
import com.amazon.redshift.core.IamHelper;
import com.amazon.redshift.core.NativeAuthPluginHelper;
import com.amazon.redshift.core.Oid;
import com.amazon.redshift.core.Provider;
import com.amazon.redshift.core.Query;
import com.amazon.redshift.core.QueryExecutor;
import com.amazon.redshift.core.RedshiftJDBCSettings;
import com.amazon.redshift.core.ReplicationProtocol;
import com.amazon.redshift.core.ResultHandlerBase;
import com.amazon.redshift.core.ServerVersion;
import com.amazon.redshift.core.SqlCommand;
import com.amazon.redshift.core.TransactionState;
import com.amazon.redshift.core.TypeInfo;
import com.amazon.redshift.core.Utils;
import com.amazon.redshift.core.Version;
import com.amazon.redshift.fastpath.Fastpath;
import com.amazon.redshift.largeobject.LargeObjectManager;
import com.amazon.redshift.logger.LogLevel;
import com.amazon.redshift.logger.RedshiftLogger;
import com.amazon.redshift.replication.RedshiftReplicationConnection;
import com.amazon.redshift.replication.RedshiftReplicationConnectionImpl;
import com.amazon.redshift.ssl.NonValidatingFactory;
import com.amazon.redshift.core.v3.QueryExecutorImpl;
import com.amazon.redshift.util.QuerySanitizer;
import com.amazon.redshift.util.ByteConverter;
import com.amazon.redshift.util.GT;
import com.amazon.redshift.util.HostSpec;
import com.amazon.redshift.util.LruCache;
import com.amazon.redshift.util.RedshiftBinaryObject;
import com.amazon.redshift.util.RedshiftConstants;
import com.amazon.redshift.util.RedshiftObject;
import com.amazon.redshift.util.RedshiftException;
import com.amazon.redshift.util.RedshiftInterval;
import com.amazon.redshift.util.RedshiftIntervalYearToMonth;
import com.amazon.redshift.util.RedshiftIntervalDayToSecond;
import com.amazon.redshift.util.RedshiftState;
import com.amazon.redshift.util.RedshiftProperties;

import java.io.IOException;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.ClientInfoStatus;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLPermission;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
// import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;

public class RedshiftConnectionImpl implements BaseConnection {

  private RedshiftLogger logger;
  private static final Set<Integer> SUPPORTED_BINARY_OIDS = getSupportedBinaryOids();
  private static final SQLPermission SQL_PERMISSION_ABORT = new SQLPermission("callAbort");
  private static final SQLPermission SQL_PERMISSION_NETWORK_TIMEOUT = new SQLPermission("setNetworkTimeout");

  // Internal properties
  private enum ReadOnlyBehavior {
    ignore,
    transaction,
    always;
  }

  //
  // Data initialized on construction:
  //
  private final Properties clientInfo;

  /* URL we were created via */
  private final String creatingURL;

  private final ReadOnlyBehavior readOnlyBehavior;

  private Throwable openStackTrace;

  /* Actual network handler */
  private final QueryExecutor queryExecutor;

  /* Query that runs COMMIT */
  private final Query commitQuery;
  /* Query that runs ROLLBACK */
  private final Query rollbackQuery;

  private final CachedQuery setSessionReadOnly;

  private final CachedQuery setSessionNotReadOnly;

  private final TypeInfo typeCache;

  private boolean disableColumnSanitiser = false;

  private boolean disableIsValidQuery = false;

  // Default statement prepare threshold.
  protected int prepareThreshold;

  // Default enable generated name for statement and portal.
  protected boolean enableGeneratedName;

  /**
   * Default fetch size for statement.
   *
   * @see RedshiftProperty#DEFAULT_ROW_FETCH_SIZE
   */
  protected int defaultFetchSize;

  // Default forcebinary option.
  protected boolean forcebinary = false;

  private int rsHoldability = ResultSet.CLOSE_CURSORS_AT_COMMIT;
  private int savepointId = 0;
  // Connection's autocommit state.
  private boolean autoCommit = true;
  // Connection's readonly state.
  private boolean readOnly = false;
  // Override getTables metadata type
  private Integer overrideSchemaPatternType ;
  // Filter out database objects for which the current user has no privileges granted from the DatabaseMetaData
  private boolean  hideUnprivilegedObjects ;
  // Bind String to UNSPECIFIED or VARCHAR?
  private final boolean bindStringAsVarchar;

  // Current warnings; there might be more on queryExecutor too.
  private SQLWarning firstWarning = null;

  // Timer for scheduling TimerTasks for this connection.
  // Only instantiated if a task is actually scheduled.
  private volatile Timer cancelTimer = null;

  /**
   * Replication protocol in current version postgresql(10devel) supports a limited number of
   * commands.
   */
  private final boolean replicationConnection;

  private final LruCache<FieldMetadata.Key, FieldMetadata> fieldMetadataCache;

  /**
   * The connection settings.
   */
  private RedshiftJDBCSettings m_settings;

  private int reWriteBatchedInsertsSize;

  private boolean databaseMetadataCurrentDbOnly;

  public static String NON_VALIDATING_SSL_FACTORY = "org.postgresql.ssl.NonValidatingFactory";

  public static final boolean IS_64_BIT_JVM = checkIs64bitJVM();

  public static final List<String> NON_IAM_PLUGINS_LIST = Collections.unmodifiableList(Arrays.asList(
          RedshiftConstants.NATIVE_IDP_AZUREAD_BROWSER_PLUGIN,
          RedshiftConstants.NATIVE_IDP_OKTA_BROWSER_PLUGIN,
          RedshiftConstants.IDP_TOKEN_PLUGIN));

  final CachedQuery borrowQuery(String sql) throws SQLException {
    return queryExecutor.borrowQuery(sql);
  }

  final CachedQuery borrowCallableQuery(String sql) throws SQLException {
    return queryExecutor.borrowCallableQuery(sql);
  }

  private CachedQuery borrowReturningQuery(String sql, String[] columnNames) throws SQLException {
    return queryExecutor.borrowReturningQuery(sql, columnNames);
  }

  @Override
  public CachedQuery createQuery(String sql, boolean escapeProcessing, boolean isParameterized,
      String... columnNames)
      throws SQLException {
    return queryExecutor.createQuery(sql, escapeProcessing, isParameterized, columnNames);
  }

  void releaseQuery(CachedQuery cachedQuery) {
    queryExecutor.releaseQuery(cachedQuery);
  }

  @Override
  public void setFlushCacheOnDeallocate(boolean flushCacheOnDeallocate) {
    queryExecutor.setFlushCacheOnDeallocate(flushCacheOnDeallocate);
  	if(RedshiftLogger.isEnable())
  		logger.log(LogLevel.DEBUG, "  setFlushCacheOnDeallocate = {0}", flushCacheOnDeallocate);
  }

  //
  // Ctor.
  //
  public RedshiftConnectionImpl(HostSpec[] hostSpecs,
                      String user,
                      String database,
                      RedshiftProperties info,
                      String url,
                      RedshiftLogger logger) throws SQLException {

  	this.logger = logger;
    // Print out the driver version number and whether its 32-bit or 64-bit JVM
  	if(RedshiftLogger.isEnable()) {
      logger.log(LogLevel.DEBUG, com.amazon.redshift.util.DriverInfo.DRIVER_FULL_NAME);
      logger.log(LogLevel.DEBUG, "JVM architecture is " + (RedshiftConnectionImpl.IS_64_BIT_JVM ? "64-bit" : "32-bit"));
    }

    RedshiftProperties.evaluateProperties(info);

    m_settings = new RedshiftJDBCSettings();

    // IAM
    boolean sslExplicitlyDisabled = setAuthMech(info);
    boolean redshiftNativeAuth = false;

    // This need to be called after setAuthMech() and before checking some required settings.
    // host, port, username and password may be set in setIAMProperties().
    String iamAuth = getOptionalSetting(RedshiftProperty.IAM_AUTH.getName(), info);
    m_settings.m_iamAuth = (iamAuth == null) ? false : Boolean.parseBoolean(iamAuth);
    if (m_settings.m_iamAuth)
    {
        String iamCredentialProvider = RedshiftConnectionImpl.getOptionalConnSetting(
            RedshiftProperty.CREDENTIALS_PROVIDER.getName(), info);
        if(iamCredentialProvider != null && iamCredentialProvider.equalsIgnoreCase(RedshiftConstants.IDP_TOKEN_PLUGIN)) {
          throw new RedshiftException(GT.tr("You can not use this authentication plugin with IAM enabled."),
                RedshiftState.UNEXPECTED_ERROR);
        }

    	if (sslExplicitlyDisabled) {
	      	throw new RedshiftException(GT.tr("SSL should be enabled in IAM authentication."),
	      			RedshiftState.UNEXPECTED_ERROR);
    	}

      if (RedshiftLogger.isEnable())
        logger.log(LogLevel.DEBUG, "Start IAM authentication");

      // Check for JWT and convert into Redshift Native Auth
      if(iamCredentialProvider != null
          && (iamCredentialProvider.equalsIgnoreCase("com.amazon.redshift.plugin.BasicJwtCredentialsProvider") ||
      iamCredentialProvider.equalsIgnoreCase("com.amazon.redshift.plugin.BasicNativeSAMLCredentialsProvider"))) {
        redshiftNativeAuth = true;
      }


       if(!redshiftNativeAuth) {
      	info = IamHelper.setIAMProperties(info, m_settings, logger);

  //      if (RedshiftLogger.isEnable())
  //        logger.log(LogLevel.DEBUG, "info after setIAMProperties" + info);

      	// Set the user name and temporary password in the property
      	RedshiftProperties updatedInfo = new RedshiftProperties();
      	updatedInfo.putAll(info);
      	if(m_settings.m_username != null) {
      		updatedInfo.put(RedshiftProperty.USER.getName(), m_settings.m_username);
      		user = m_settings.m_username;
      	}
      	if(m_settings.m_password != null)
      		updatedInfo.put(RedshiftProperty.PASSWORD.getName(), m_settings.m_password);

      	if(m_settings.m_host != null) {
      		updatedInfo.putIfAbsent(RedshiftProperty.HOST.getName(), m_settings.m_host);
      	}

      	if(m_settings.m_port != 0) {
      		updatedInfo.putIfAbsent(RedshiftProperty.PORT.getName(), String.valueOf(m_settings.m_port));
      	}

      	if (hostSpecs == null) {
      		hostSpecs = Driver.hostSpecs(updatedInfo);
      	}

      	info = updatedInfo;
       } // !Redshift Native Auth
    } // IAM auth
    else
    {
      // Check for non IAM authentication plugins
      String nonIamCredentialProvider = RedshiftConnectionImpl.getOptionalConnSetting(
          RedshiftProperty.CREDENTIALS_PROVIDER.getName(),
          info);

      if (nonIamCredentialProvider != null
              && NON_IAM_PLUGINS_LIST.stream().anyMatch(nonIamCredentialProvider::equalsIgnoreCase)) {
        redshiftNativeAuth = true;
        if (sslExplicitlyDisabled) {
          throw new RedshiftException(GT.tr("Authentication must use an SSL connection."),
                  RedshiftState.UNEXPECTED_ERROR);
        }

        // Call OAuth2 plugin and get the access token
        info = NativeAuthPluginHelper.setNativeAuthPluginProperties(info, m_settings, logger);
      }
    }

    this.creatingURL = url;

    this.readOnlyBehavior = getReadOnlyBehavior(RedshiftProperty.READ_ONLY_MODE.get(info));

    int dfltRowFetchSizeProp = RedshiftProperty.DEFAULT_ROW_FETCH_SIZE.getInt(info);
    int blockingRowsMode = RedshiftProperty.BLOCKING_ROWS_MODE.getInt(info);
    int dfltRowFetchSize = (dfltRowFetchSizeProp != 0) ? dfltRowFetchSizeProp : blockingRowsMode;
    setDefaultFetchSize(dfltRowFetchSize);

    setPrepareThreshold(RedshiftProperty.PREPARE_THRESHOLD.getInt(info));
    if (prepareThreshold == -1) {
      setForceBinary(true);
    }

    setGeneratedName(RedshiftProperty.ENABLE_GENERATED_NAME_FOR_PREPARED_STATEMENT.getBoolean(info));

    // Now make the initial connection and set up local state
    this.queryExecutor = ConnectionFactory.openConnection(hostSpecs, user, database, info, logger);

    setSessionReadOnly = createQuery("SET readonly=1", false, true); // SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY
    setSessionNotReadOnly = createQuery("SET readonly=0", false, true); // SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE

    // Set read-only early if requested
    if (RedshiftProperty.READ_ONLY.getBoolean(info)) {
      setReadOnly(true);
    }

    this.databaseMetadataCurrentDbOnly = RedshiftProperty.DATABASE_METADATA_CURRENT_DB_ONLY.getBoolean(info);

    this.hideUnprivilegedObjects = RedshiftProperty.HIDE_UNPRIVILEGED_OBJECTS.getBoolean(info);

    this.overrideSchemaPatternType = RedshiftProperty.OVERRIDE_SCHEMA_PATTERN_TYPE.getInteger(info);

    this.reWriteBatchedInsertsSize = RedshiftProperty.REWRITE_BATCHED_INSERTS_SIZE.getInt(info);

    Set<Integer> binaryOids = getBinaryOids(info);

    // split for receive and send for better control
    Set<Integer> useBinarySendForOids = new HashSet<Integer>(binaryOids);

    Set<Integer> useBinaryReceiveForOids = new HashSet<Integer>(binaryOids);

    /*
     * Does not pass unit tests because unit tests expect setDate to have millisecond accuracy
     * whereas the binary transfer only supports date accuracy.
     */
    useBinarySendForOids.remove(Oid.DATE);

    queryExecutor.setBinaryReceiveOids(useBinaryReceiveForOids);
    queryExecutor.setBinarySendOids(useBinarySendForOids);

    if (RedshiftLogger.isEnable()) {
      logger.log(LogLevel.DEBUG, "    types using binary send = {0}", oidsToString(useBinarySendForOids));
      logger.log(LogLevel.DEBUG, "    types using binary receive = {0}", oidsToString(useBinaryReceiveForOids));
      logger.log(LogLevel.DEBUG, "    integer date/time = {0}", queryExecutor.getIntegerDateTimes());
    }

    queryExecutor.setRaiseExceptionOnSilentRollback(
        RedshiftProperty.RAISE_EXCEPTION_ON_SILENT_ROLLBACK.getBoolean(info)
    );

    //
    // String -> text or unknown?
    //

    String stringType = RedshiftProperty.STRING_TYPE.get(info);
    if (stringType != null) {
      if (stringType.equalsIgnoreCase("unspecified")) {
        bindStringAsVarchar = false;
      } else if (stringType.equalsIgnoreCase("varchar")) {
        bindStringAsVarchar = true;
      } else {
        throw new RedshiftException(
            GT.tr("Unsupported value for stringtype parameter: {0}", stringType),
            RedshiftState.INVALID_PARAMETER_VALUE);
      }
    } else {
      bindStringAsVarchar = true;
    }

    // Initialize timestamp stuff
    timestampUtils = new TimestampUtils(!queryExecutor.getIntegerDateTimes(), new Provider<TimeZone>() {
      @Override
      public TimeZone get() {
        return queryExecutor.getTimeZone();
      }
    });

    // Initialize common queries.
    // isParameterized==true so full parse is performed and the engine knows the query
    // is not a compound query with ; inside, so it could use parse/bind/exec messages
    commitQuery = createQuery("COMMIT", false, true).query;
    rollbackQuery = createQuery("ROLLBACK", false, true).query;

    int unknownLength = RedshiftProperty.UNKNOWN_LENGTH.getInt(info);

    // Initialize object handling
    typeCache = createTypeInfo(this, unknownLength);
    initObjectTypes(info);

    if (RedshiftProperty.LOG_UNCLOSED_CONNECTIONS.getBoolean(info)) {
      openStackTrace = new Throwable("Connection was created at this point:");
    }
    this.disableColumnSanitiser = RedshiftProperty.DISABLE_COLUMN_SANITISER.getBoolean(info);
    this.disableIsValidQuery = RedshiftProperty.DISABLE_ISVALID_QUERY.getBoolean(info);


/*    if (haveMinimumServerVersion(ServerVersion.v8_3)) {
      typeCache.addCoreType("uuid", Oid.UUID, Types.OTHER, "java.util.UUID", Oid.UUID_ARRAY);
      typeCache.addCoreType("xml", Oid.XML, Types.SQLXML, "java.sql.SQLXML", Oid.XML_ARRAY);
    } */

    this.clientInfo = new Properties();
/*    if (haveMinimumServerVersion(ServerVersion.v9_0)) */
    {
      String appName = RedshiftProperty.APPLICATION_NAME.get(info);
      if (appName == null) {
        appName = "";
      }
      this.clientInfo.put("ApplicationName", appName);
    }

    fieldMetadataCache = new LruCache<FieldMetadata.Key, FieldMetadata>(
            Math.max(0, RedshiftProperty.DATABASE_METADATA_CACHE_FIELDS.getInt(info)),
            Math.max(0, RedshiftProperty.DATABASE_METADATA_CACHE_FIELDS_MIB.getInt(info) * 1024 * 1024),
        false);

    replicationConnection = RedshiftProperty.REPLICATION.get(info) != null;
  }

  private static ReadOnlyBehavior getReadOnlyBehavior(String property) {
    try {
      return ReadOnlyBehavior.valueOf(property);
    } catch (IllegalArgumentException e) {
      try {
        return ReadOnlyBehavior.valueOf(property.toLowerCase(Locale.US));
      } catch (IllegalArgumentException e2) {
        return ReadOnlyBehavior.transaction;
      }
    }
  }

  private static Set<Integer> getSupportedBinaryOids() {
    return new HashSet<Integer>(Arrays.asList(
        Oid.BYTEA,
        Oid.INT2,
        Oid.INT4,
        Oid.INT8,
        Oid.FLOAT4,
        Oid.FLOAT8,
        Oid.TIME,
        Oid.DATE,
        Oid.TIMETZ,
        Oid.TIMESTAMP,
        Oid.TIMESTAMPTZ,
        Oid.INTERVALY2M,
        Oid.INTERVALD2S,
        Oid.INT2_ARRAY,
        Oid.INT4_ARRAY,
        Oid.INT8_ARRAY,
        Oid.FLOAT4_ARRAY,
        Oid.FLOAT8_ARRAY,
        Oid.VARCHAR_ARRAY,
        Oid.TEXT_ARRAY,
        Oid.POINT,
        Oid.BOX,
        Oid.UUID));
  }

  private static Set<Integer> getBinaryOids(Properties info) throws RedshiftException {
    boolean binaryTransfer = RedshiftProperty.BINARY_TRANSFER.getBoolean(info);
    // Formats that currently have binary protocol support
    Set<Integer> binaryOids = new HashSet<Integer>(32);
    if (binaryTransfer) {
      binaryOids.addAll(SUPPORTED_BINARY_OIDS);
    }

    binaryOids.addAll(getOidSet(RedshiftProperty.BINARY_TRANSFER_ENABLE.get(info)));
    binaryOids.removeAll(getOidSet(RedshiftProperty.BINARY_TRANSFER_DISABLE.get(info)));
    binaryOids.retainAll(SUPPORTED_BINARY_OIDS);
    return binaryOids;
  }

  private static Set<Integer> getOidSet(String oidList) throws RedshiftException {
    Set<Integer> oids = new HashSet<Integer>();
    StringTokenizer tokenizer = new StringTokenizer(oidList, ",");
    while (tokenizer.hasMoreTokens()) {
      String oid = tokenizer.nextToken();
      oids.add(Oid.valueOf(oid));
    }
    return oids;
  }

  private String oidsToString(Set<Integer> oids) {
    StringBuilder sb = new StringBuilder();
    for (Integer oid : oids) {
      sb.append(Oid.toString(oid));
      sb.append(',');
    }
    if (sb.length() > 0) {
      sb.setLength(sb.length() - 1);
    } else {
      sb.append(" <none>");
    }
    return sb.toString();
  }

  private final TimestampUtils timestampUtils;

  public TimestampUtils getTimestampUtils() {
    return timestampUtils;
  }

  /**
   * The current type mappings.
   */
  protected Map<String, Class<?>> typemap;

  @Override
  public Statement createStatement() throws SQLException {
    if (RedshiftLogger.isEnable())
    	logger.logFunction(true);

    // We now follow the spec and default to TYPE_FORWARD_ONLY.
    Statement stmt = createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

    if (RedshiftLogger.isEnable())
    	logger.logFunction(false, stmt);

    return stmt;
  }

  @Override
  public PreparedStatement prepareStatement(String sql) throws SQLException {

  	if (RedshiftLogger.isEnable())
    {
      logger.logFunction(true, QuerySanitizer.filterCredentials(sql));
    }

    PreparedStatement pstmt = prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

    if (RedshiftLogger.isEnable())
    	logger.logFunction(false, QuerySanitizer.filterCredentials(pstmt.toString()));

    return pstmt;
  }

  @Override
  public CallableStatement prepareCall(String sql) throws SQLException {

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true, QuerySanitizer.filterCredentials(sql));

    CallableStatement cstmt = prepareCall(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

    if (RedshiftLogger.isEnable())
    	logger.logFunction(false, QuerySanitizer.filterCredentials(cstmt.toString()));

    return cstmt;
  }

  @Override
  public Map<String, Class<?>> getTypeMap() throws SQLException {
    checkClosed();
    return typemap;
  }

  public QueryExecutor getQueryExecutor() {
    return queryExecutor;
  }

  public ReplicationProtocol getReplicationProtocol() {
    return queryExecutor.getReplicationProtocol();
  }

  /**
   * This adds a warning to the warning chain.
   *
   * @param warn warning to add
   */
  public void addWarning(SQLWarning warn) {
    // Add the warning to the chain
    if (firstWarning != null) {
      firstWarning.setNextWarning(warn);
    } else {
      firstWarning = warn;
    }

  }

  @Override
  public ResultSet execSQLQuery(String s) throws SQLException {
    return execSQLQuery(s, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
  }

  @Override
  public Long getBytesReadFromStream()
  {
    RedshiftConnectionImpl redshiftConnectionImpl = this;
    if(null != redshiftConnectionImpl && null != redshiftConnectionImpl.getQueryExecutor())
    {
      QueryExecutorImpl queryExecutorImpl = (QueryExecutorImpl) redshiftConnectionImpl.getQueryExecutor();
      long bytes = queryExecutorImpl.getBytesReadFromStream();
      return bytes;
    }

    return 0L;
  }

  @Override
  public ResultSet execSQLQuery(String s, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    BaseStatement stat = (BaseStatement) createStatement(resultSetType, resultSetConcurrency);
    boolean hasResultSet = stat.executeWithFlags(s, QueryExecutor.QUERY_SUPPRESS_BEGIN);

    while (!hasResultSet && stat.getUpdateCount() != -1) {
      hasResultSet = stat.getMoreResults();
    }

    if (!hasResultSet) {
      throw new RedshiftException(GT.tr("No results were returned by the query."), RedshiftState.NO_DATA);
    }

    // Transfer warnings to the connection, since the user never
    // has a chance to see the statement itself.
    SQLWarning warnings = stat.getWarnings();
    if (warnings != null) {
      addWarning(warnings);
    }

    return stat.getResultSet();
  }

  @Override
  public void execSQLUpdate(String s) throws SQLException {
    BaseStatement stmt = (BaseStatement) createStatement();
    if (stmt.executeWithFlags(s, QueryExecutor.QUERY_NO_METADATA | QueryExecutor.QUERY_NO_RESULTS
        | QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new RedshiftException(GT.tr("A result was returned when none was expected."),
          RedshiftState.TOO_MANY_RESULTS);
    }

    // Transfer warnings to the connection, since the user never
    // has a chance to see the statement itself.
    SQLWarning warnings = stmt.getWarnings();
    if (warnings != null) {
      addWarning(warnings);
    }

    stmt.close();
  }

  void execSQLUpdate(CachedQuery query) throws SQLException {
    BaseStatement stmt = (BaseStatement) createStatement();
    if (stmt.executeWithFlags(query, QueryExecutor.QUERY_NO_METADATA | QueryExecutor.QUERY_NO_RESULTS
        | QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new RedshiftException(GT.tr("A result was returned when none was expected."),
          RedshiftState.TOO_MANY_RESULTS);
    }

    // Transfer warnings to the connection, since the user never
    // has a chance to see the statement itself.
    SQLWarning warnings = stmt.getWarnings();
    if (warnings != null) {
      addWarning(warnings);
    }

    stmt.close();
  }

  /**
   * <p>In SQL, a result table can be retrieved through a cursor that is named. The current row of a
   * result can be updated or deleted using a positioned update/delete statement that references the
   * cursor name.</p>
   *
   * <p>We do not support positioned update/delete, so this is a no-op.</p>
   *
   * @param cursor the cursor name
   * @throws SQLException if a database access error occurs
   */
  public void setCursorName(String cursor) throws SQLException {
    checkClosed();
    // No-op.
  }

  /**
   * getCursorName gets the cursor name.
   *
   * @return the current cursor name
   * @throws SQLException if a database access error occurs
   */
  public String getCursorName() throws SQLException {
    checkClosed();
    return null;
  }

  /**
   * <p>We are required to bring back certain information by the DatabaseMetaData class. These
   * functions do that.</p>
   *
   * <p>Method getURL() brings back the URL (good job we saved it)</p>
   *
   * @return the url
   * @throws SQLException just in case...
   */
  public String getURL() throws SQLException {
    return creatingURL;
  }

  /**
   * Method getUserName() brings back the User Name (again, we saved it).
   *
   * @return the user name
   * @throws SQLException just in case...
   */
  public String getUserName() throws SQLException {
    return queryExecutor.getUser();
  }

  public Fastpath getFastpathAPI() throws SQLException {
    checkClosed();
    if (fastpath == null) {
      fastpath = new Fastpath(this);
    }
    return fastpath;
  }

  // This holds a reference to the Fastpath API if already open
  private Fastpath fastpath = null;

  public LargeObjectManager getLargeObjectAPI() throws SQLException {
    checkClosed();
    if (largeobject == null) {
      largeobject = new LargeObjectManager(this);
    }
    return largeobject;
  }

  // This holds a reference to the LargeObject API if already open
  private LargeObjectManager largeobject = null;

  /*
   * This method is used internally to return an object based around com.amazon.redshift's more unique
   * data types.
   *
   * <p>It uses an internal HashMap to get the handling class. If the type is not supported, then an
   * instance of com.amazon.redshift.util.RedshiftObject is returned.
   *
   * You can use the getValue() or setValue() methods to handle the returned object. Custom objects
   * can have their own methods.
   *
   * @return RedshiftObject for this type, and set to value
   *
   * @exception SQLException if value is not correct for this type
   */
  @Override
  public Object getObject(String type, String value, byte[] byteValue) throws SQLException {
    if (typemap != null) {
      Class<?> c = typemap.get(type);
      if (c != null) {
        // Handle the type (requires SQLInput & SQLOutput classes to be implemented)
        throw new RedshiftException(GT.tr("Custom type maps are not supported."),
            RedshiftState.NOT_IMPLEMENTED);
      }
    }

    RedshiftObject obj = null;

    if (RedshiftLogger.isEnable()) {
      logger.log(LogLevel.DEBUG, "Constructing object from type={0} value=<{1}>", new Object[]{type, value});
    }

    try {
      Class<? extends RedshiftObject> klass = typeCache.getRSobject(type);

      // If className is not null, then try to instantiate it,
      // It must be basetype RedshiftObject

      // This is used to implement the com.amazon.redshift unique types (like lseg,
      // point, etc).

      if (klass != null) {
        obj = klass.newInstance();
        obj.setType(type);
        if (byteValue != null && obj instanceof RedshiftBinaryObject) {
          RedshiftBinaryObject binObj = (RedshiftBinaryObject) obj;
          binObj.setByteValue(byteValue, 0);
        }
        else if (byteValue != null && obj instanceof RedshiftInterval) {
        	RedshiftInterval intervalObj = (RedshiftInterval) obj;

        	// Binary format is 8 bytes time and 4 byes months
        	long time = ByteConverter.int8(byteValue, 0);
        	int month = ByteConverter.int4(byteValue, 8);

        	intervalObj.setValue(month, time);

//        	intervalObj.setValue(new String(byteValue));
        }
        else {
          obj.setValue(value);
        }
      } else {
        // If className is null, then the type is unknown.
        // so return a RedshiftOject with the type set, and the value set
        obj = new RedshiftObject();
        obj.setType(type);
        obj.setValue(value);
      }

      return obj;
    } catch (SQLException sx) {
      // rethrow the exception. Done because we capture any others next
      throw sx;
    } catch (Exception ex) {
      throw new RedshiftException(GT.tr("Failed to create object for: {0}.", type),
          RedshiftState.CONNECTION_FAILURE, ex);
    }
  }

  protected TypeInfo createTypeInfo(BaseConnection conn, int unknownLength) {
    return new TypeInfoCache(conn, unknownLength);
  }

  public TypeInfo getTypeInfo() {
    return typeCache;
  }

  @Override
  public void addDataType(String type, String name) {
    try {
      addDataType(type, Class.forName(name).asSubclass(RedshiftObject.class));
    } catch (Exception e) {
      throw new RuntimeException("Cannot register new type: " + e);
    }
  }

  @Override
  public void addDataType(String type, Class<? extends RedshiftObject> klass) throws SQLException {
    checkClosed();
    typeCache.addDataType(type, klass);
  }

  // This initialises the objectTypes hash map
  private void initObjectTypes(Properties info) throws SQLException {
    // Add in the types that come packaged with the driver.
    // These can be overridden later if desired.
    addDataType("box", com.amazon.redshift.geometric.RedshiftBox.class);
    addDataType("circle", com.amazon.redshift.geometric.RedshiftCircle.class);
    addDataType("line", com.amazon.redshift.geometric.RedshiftLine.class);
    addDataType("lseg", com.amazon.redshift.geometric.RedshiftLseg.class);
    addDataType("path", com.amazon.redshift.geometric.RedshiftPath.class);
    addDataType("point", com.amazon.redshift.geometric.RedshiftPoint.class);
    addDataType("polygon", com.amazon.redshift.geometric.RedshiftPolygon.class);
    addDataType("money", com.amazon.redshift.util.RedshiftMoney.class);
    addDataType("interval", com.amazon.redshift.util.RedshiftInterval.class);
    // intervaly2m and intervald2s are not object types rather they are
    // binary types native to Redshift, hence they are added in TypeInfoCache.

    Enumeration<?> e = info.propertyNames();
    while (e.hasMoreElements()) {
      String propertyName = (String) e.nextElement();
      if (propertyName.startsWith("datatype.")) {
        String typeName = propertyName.substring(9);
        String className = info.getProperty(propertyName);
        Class<?> klass;

        try {
          klass = Class.forName(className);
        } catch (ClassNotFoundException cnfe) {
          throw new RedshiftException(
              GT.tr("Unable to load the class {0} responsible for the datatype {1}",
                  className, typeName),
              RedshiftState.SYSTEM_ERROR, cnfe);
        }

        addDataType(typeName, klass.asSubclass(RedshiftObject.class));
      }
    }
  }

  /**
   * <B>Note:</B> even though {@code Statement} is automatically closed when it is garbage
   * collected, it is better to close it explicitly to lower resource consumption.
   *
   * {@inheritDoc}
   */
  @Override
  public void close() throws SQLException {

    if (RedshiftLogger.isEnable())
      logger.logFunction(true);

    if (queryExecutor == null) {
      // This might happen in case constructor throws an exception (e.g. host being not available).
      // When that happens the connection is still registered in the finalizer queue, so it gets finalized
      if (RedshiftLogger.isEnable()) {
      	logger.logFunction(false);
      	logger.close();
      }

      return;
    }
    releaseTimer();
    queryExecutor.close();
    openStackTrace = null;

    // Close the logger stream
    if(RedshiftLogger.isEnable()) {
    	logger.logFunction(false);
    	logger.close();
    }
  }

  @Override
  public String nativeSQL(String sql) throws SQLException {
    checkClosed();
    CachedQuery cachedQuery = queryExecutor.createQuery(sql, false, true);

    return cachedQuery.query.getNativeSql();
  }

  @Override
  public synchronized SQLWarning getWarnings() throws SQLException {
    checkClosed();
    SQLWarning newWarnings = queryExecutor.getWarnings(); // NB: also clears them.
    if (firstWarning == null) {
      firstWarning = newWarnings;
    } else {
      firstWarning.setNextWarning(newWarnings); // Chain them on.
    }

    return firstWarning;
  }

  @Override
  public synchronized void clearWarnings() throws SQLException {
    checkClosed();
    queryExecutor.getWarnings(); // Clear and discard.
    firstWarning = null;
  }

  public void setDatabaseMetadataCurrentDbOnly(boolean databaseMetadataCurrentDbOnly) throws SQLException {
  	this.databaseMetadataCurrentDbOnly = databaseMetadataCurrentDbOnly;
  }

  public boolean isDatabaseMetadataCurrentDbOnly() {
  	return databaseMetadataCurrentDbOnly;
  }

  @Override
  public void setReadOnly(boolean readOnly) throws SQLException {

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true, readOnly);

    checkClosed();
    if (queryExecutor.getTransactionState() != TransactionState.IDLE) {
      throw new RedshiftException(
          GT.tr("Cannot change transaction read-only property in the middle of a transaction."),
          RedshiftState.ACTIVE_SQL_TRANSACTION);
    }

    if (readOnly != this.readOnly && autoCommit && this.readOnlyBehavior == ReadOnlyBehavior.always) {
      execSQLUpdate(readOnly ? setSessionReadOnly : setSessionNotReadOnly);
    }

    this.readOnly = readOnly;
    if(RedshiftLogger.isEnable())
    	logger.log(LogLevel.DEBUG, "  setReadOnly = {0}", readOnly);
  }

  @Override
  public boolean isReadOnly() throws SQLException {

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true);

    checkClosed();

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(false, readOnly);

    return readOnly;
  }

  @Override
  public boolean hintReadOnly() {
    return readOnly && readOnlyBehavior != ReadOnlyBehavior.ignore;
  }

  @Override
  public void setAutoCommit(boolean autoCommit) throws SQLException {

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true, autoCommit);

    checkClosed();

    if (this.autoCommit == autoCommit) {
      return;
    }

    if (!this.autoCommit) {
      commit();
    }

    // if the connection is read only, we need to make sure session settings are
    // correct when autocommit status changed
    if (this.readOnly && readOnlyBehavior == ReadOnlyBehavior.always) {
      // if we are turning on autocommit, we need to set session
      // to read only
      if (autoCommit) {
        this.autoCommit = true;
        execSQLUpdate(setSessionReadOnly);
      } else {
        // if we are turning auto commit off, we need to
        // disable session
        execSQLUpdate(setSessionNotReadOnly);
      }
    }

    this.autoCommit = autoCommit;

    if(RedshiftLogger.isEnable()) {
    	logger.log(LogLevel.DEBUG, "  setAutoCommit = {0}", autoCommit);
    	logger.logFunction(false);
    }
  }

  @Override
  public boolean getAutoCommit() throws SQLException {

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true);

    checkClosed();
    boolean rc = this.autoCommit;

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(false, rc);

    return rc;
  }

  private void executeTransactionCommand(Query query) throws SQLException {
    int flags = QueryExecutor.QUERY_NO_METADATA | QueryExecutor.QUERY_NO_RESULTS
        | QueryExecutor.QUERY_SUPPRESS_BEGIN;
    if (prepareThreshold == 0) {
      flags |= QueryExecutor.QUERY_ONESHOT;
    }

    try {
      getQueryExecutor().execute(query, null, new TransactionCommandHandler(), 0, 0, flags);
    } catch (SQLException e) {
      // Don't retry composite queries as it might get partially executed
      if (query.getSubqueries() != null || !queryExecutor.willHealOnRetry(e)) {
        throw e;
      }
      query.close();
      // retry
      getQueryExecutor().execute(query, null, new TransactionCommandHandler(), 0, 0, flags);
    }
  }

  @Override
  public void commit() throws SQLException {

    if (RedshiftLogger.isEnable())
    	logger.logFunction(true);

    checkClosed();

    if (autoCommit) {
      throw new RedshiftException(GT.tr("Cannot commit when autoCommit is enabled."),
          RedshiftState.NO_ACTIVE_SQL_TRANSACTION);
    }

    if (queryExecutor.getTransactionState() != TransactionState.IDLE) {
      executeTransactionCommand(commitQuery);
    }

    if (RedshiftLogger.isEnable())
    	logger.logFunction(false);
  }

  protected void checkClosed() throws SQLException {
    if (isClosed()) {
      throw new RedshiftException(GT.tr("This connection has been closed."),
          RedshiftState.CONNECTION_DOES_NOT_EXIST);
    }
  }

  @Override
  public void rollback() throws SQLException {

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true);

    checkClosed();

    if (autoCommit) {
      throw new RedshiftException(GT.tr("Cannot rollback when autoCommit is enabled."),
          RedshiftState.NO_ACTIVE_SQL_TRANSACTION);
    }

    if (queryExecutor.getTransactionState() != TransactionState.IDLE) {
      executeTransactionCommand(rollbackQuery);
    } else {
      // just log for debugging
      if(RedshiftLogger.isEnable())
      	logger.log(LogLevel.DEBUG, "Rollback requested but no transaction in progress");
    }

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(false);
  }

  public TransactionState getTransactionState() {
    return queryExecutor.getTransactionState();
  }

  public int getTransactionIsolation() throws SQLException {
  	return Connection.TRANSACTION_SERIALIZABLE;
  }

  public void setTransactionIsolation(int level) throws SQLException {

    if(RedshiftLogger.isEnable())
    	logger.logFunction(true, level);

    checkClosed();

    if (queryExecutor.getTransactionState() != TransactionState.IDLE) {
      throw new RedshiftException(
          GT.tr("Cannot change transaction isolation level in the middle of a transaction."),
          RedshiftState.ACTIVE_SQL_TRANSACTION);
    }

    String isolationLevelName = getIsolationLevelName(level);
    if (isolationLevelName == null) {
      throw new RedshiftException(GT.tr("Transaction isolation level {0} not supported.", level),
          RedshiftState.NOT_IMPLEMENTED);
    }

    String isolationLevelSQL =
        "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL " + isolationLevelName;
    execSQLUpdate(isolationLevelSQL); // nb: no BEGIN triggered

    if(RedshiftLogger.isEnable())
    	logger.log(LogLevel.DEBUG, "  setTransactionIsolation = {0}", isolationLevelName);
  }

  protected String getIsolationLevelName(int level) {
    switch (level) {
      case Connection.TRANSACTION_READ_COMMITTED:
        return "READ COMMITTED";
      case Connection.TRANSACTION_SERIALIZABLE:
        return "SERIALIZABLE";
      case Connection.TRANSACTION_READ_UNCOMMITTED:
        return "READ UNCOMMITTED";
      case Connection.TRANSACTION_REPEATABLE_READ:
        return "REPEATABLE READ";
      default:
        return null;
    }
  }

  public void setCatalog(String catalog) throws SQLException {
    checkClosed();
    // no-op
  }

  public String getCatalog() throws SQLException {
    checkClosed();
    return queryExecutor.getDatabase();
  }

  public boolean getHideUnprivilegedObjects() {
    return hideUnprivilegedObjects;
  }

  /**
   * <p>Overrides finalize(). If called, it closes the connection.</p>
   *
   * <p>This was done at the request of <a href="mailto:rachel@enlarion.demon.co.uk">Rachel
   * Greenham</a> who hit a problem where multiple clients didn't close the connection, and once a
   * fortnight enough clients were open to kill the postgres server.</p>
   */
  protected void finalize() throws Throwable {
    try {
      if (openStackTrace != null) {
        if(RedshiftLogger.isEnable())
        	logger.log(LogLevel.INFO, GT.tr("Finalizing a Connection that was never closed:"), openStackTrace);
      }

      close();
    } finally {
      super.finalize();
    }
  }

  /**
   * Get server version number.
   *
   * @return server version number
   */
  public String getDBVersionNumber() {
    return queryExecutor.getServerVersion();
  }

  /**
   * Get server major version.
   *
   * @return server major version
   */
  public int getServerMajorVersion() {
    try {
      StringTokenizer versionTokens = new StringTokenizer(queryExecutor.getServerVersion(), "."); // aaXbb.ccYdd
      return integerPart(versionTokens.nextToken()); // return X
    } catch (NoSuchElementException e) {
      return 0;
    }
  }

  /**
   * Get server minor version.
   *
   * @return server minor version
   */
  public int getServerMinorVersion() {
    try {
      StringTokenizer versionTokens = new StringTokenizer(queryExecutor.getServerVersion(), "."); // aaXbb.ccYdd
      versionTokens.nextToken(); // Skip aaXbb
      return integerPart(versionTokens.nextToken()); // return Y
    } catch (NoSuchElementException e) {
      return 0;
    }
  }

  @Override
  public boolean haveMinimumServerVersion(int ver) {
    return queryExecutor.getServerVersionNum() >= ver;
  }

  @Override
  public boolean haveMinimumServerVersion(Version ver) {
    return haveMinimumServerVersion(ver.getVersionNum());
  }

  @Override
  public Encoding getEncoding() {
    return queryExecutor.getEncoding();
  }

  @Override
  public byte[] encodeString(String str) throws SQLException {
    try {
      return getEncoding().encode(str);
    } catch (IOException ioe) {
      throw new RedshiftException(GT.tr("Unable to translate data into the desired encoding."),
          RedshiftState.DATA_ERROR, ioe);
    }
  }

  @Override
  public String escapeString(String str) throws SQLException {
    return Utils.escapeLiteral(null, str, queryExecutor.getStandardConformingStrings())
        .toString();
  }

  @Override
  public String escapeOnlyQuotesString(String str) throws SQLException {
    return Utils.escapeLiteral(null, str, queryExecutor.getStandardConformingStrings(),true)
        .toString();
  }

  @Override
  public boolean getStandardConformingStrings() {
    return queryExecutor.getStandardConformingStrings();
  }

  // This is a cache of the DatabaseMetaData instance for this connection
  protected java.sql.DatabaseMetaData metadata;

  @Override
  public boolean isClosed() throws SQLException {
    return queryExecutor.isClosed();
  }

  @Override
  public void cancelQuery() throws SQLException {
    checkClosed();

    queryExecutor.sendQueryCancel();

  	if (RedshiftLogger.isEnable())
  		logger.logError("Send query cancel to server");
  }

  @Override
  public RedshiftNotification[] getNotifications() throws SQLException {
    return getNotifications(-1);
  }

  @Override
  public RedshiftNotification[] getNotifications(int timeoutMillis) throws SQLException {
    checkClosed();
    getQueryExecutor().processNotifies(timeoutMillis);
    // Backwards-compatibility hand-holding.
    RedshiftNotification[] notifications = queryExecutor.getNotifications();
    return (notifications.length == 0 ? null : notifications);
  }

  /**
   * Handler for transaction queries.
   */
  private class TransactionCommandHandler extends ResultHandlerBase {
    public void handleCompletion() throws SQLException {
      SQLWarning warning = getWarning();
      if (warning != null) {
        RedshiftConnectionImpl.this.addWarning(warning);
      }
      super.handleCompletion();
    }
  }

  public int getPrepareThreshold() {
    return prepareThreshold;
  }

  public void setDefaultFetchSize(int fetchSize) throws SQLException {
    if (fetchSize < 0) {
      throw new RedshiftException(GT.tr("Fetch size must be a value greater to or equal to 0."),
          RedshiftState.INVALID_PARAMETER_VALUE);
    }

    this.defaultFetchSize = fetchSize;
    if(RedshiftLogger.isEnable())
    	logger.log(LogLevel.DEBUG, "  setDefaultFetchSize = {0}", fetchSize);
  }

  public int getDefaultFetchSize() {
    return defaultFetchSize;
  }

  public int getReWriteBatchedInsertsSize() {
    return this.reWriteBatchedInsertsSize;
  }

  public Integer getOverrideSchemaPatternType() {
    return this.overrideSchemaPatternType;
  }

  public void setPrepareThreshold(int newThreshold) {
    this.prepareThreshold = newThreshold;
    if(RedshiftLogger.isEnable())
    	logger.log(LogLevel.DEBUG, "  setPrepareThreshold = {0}", newThreshold);
  }

  public void setGeneratedName(boolean enable) {
  	enableGeneratedName = enable;
  }

  public boolean getGeneratedName() {
    return enableGeneratedName;
  }

  public boolean getForceBinary() {
    return forcebinary;
  }

  public void setForceBinary(boolean newValue) {
    this.forcebinary = newValue;
    if(RedshiftLogger.isEnable())
    	logger.log(LogLevel.DEBUG, "  setForceBinary = {0}", newValue);
  }

  public void setTypeMapImpl(Map<String, Class<?>> map) throws SQLException {
    typemap = map;
  }

  public RedshiftLogger getLogger() {
    return logger;
  }

  public int getProtocolVersion() {
    return queryExecutor.getProtocolVersion();
  }

  public boolean getStringVarcharFlag() {
    return bindStringAsVarchar;
  }

  private CopyManager copyManager = null;

  public CopyManager getCopyAPI() throws SQLException {
    checkClosed();
    if (copyManager == null) {
      copyManager = new CopyManager(this);
    }
    return copyManager;
  }

  public boolean binaryTransferSend(int oid) {
    return queryExecutor.useBinaryForSend(oid);
  }

  public int getBackendPID() {
    return queryExecutor.getBackendPID();
  }

  public boolean isColumnSanitiserDisabled() {
    return this.disableColumnSanitiser;
  }

  public void setDisableColumnSanitiser(boolean disableColumnSanitiser) {
    this.disableColumnSanitiser = disableColumnSanitiser;
    if(RedshiftLogger.isEnable())
    	logger.log(LogLevel.DEBUG, "  setDisableColumnSanitiser = {0}", disableColumnSanitiser);
  }

  @Override
  public PreferQueryMode getPreferQueryMode() {
    return queryExecutor.getPreferQueryMode();
  }

  @Override
  public AutoSave getAutosave() {
    return queryExecutor.getAutoSave();
  }

  @Override
  public void setAutosave(AutoSave autoSave) {
    queryExecutor.setAutoSave(autoSave);
    if(RedshiftLogger.isEnable())
    	logger.log(LogLevel.DEBUG, "  setAutosave = {0}", autoSave.value());
  }

  protected void abort() {
    queryExecutor.abort();
  }

  private synchronized Timer getTimer() {
    if (cancelTimer == null) {
      cancelTimer = Driver.getSharedTimer().getTimer();
    }
    return cancelTimer;
  }

  private synchronized void releaseTimer() {
    if (cancelTimer != null) {
      cancelTimer = null;
      Driver.getSharedTimer().releaseTimer();
    }
  }

  @Override
  public void addTimerTask(TimerTask timerTask, long milliSeconds) {
    Timer timer = getTimer();
    timer.schedule(timerTask, milliSeconds);
  }

  @Override
  public void purgeTimerTasks() {
    Timer timer = cancelTimer;
    if (timer != null) {
      timer.purge();
    }
  }

  @Override
  public String escapeIdentifier(String identifier) throws SQLException {
    return Utils.escapeIdentifier(null, identifier).toString();
  }

  @Override
  public String escapeLiteral(String literal) throws SQLException {
    return Utils.escapeLiteral(null, literal, queryExecutor.getStandardConformingStrings())
        .toString();
  }

  @Override
  public LruCache<FieldMetadata.Key, FieldMetadata> getFieldMetadataCache() {
    return fieldMetadataCache;
  }

  @Override
  public RedshiftReplicationConnection getReplicationAPI() {
    return new RedshiftReplicationConnectionImpl(this);
  }

  private static void appendArray(StringBuilder sb, Object elements, char delim) {
    sb.append('{');

    int nElements = java.lang.reflect.Array.getLength(elements);
    for (int i = 0; i < nElements; i++) {
      if (i > 0) {
        sb.append(delim);
      }

      Object o = java.lang.reflect.Array.get(elements, i);
      if (o == null) {
        sb.append("NULL");
      } else if (o.getClass().isArray()) {
        final PrimitiveArraySupport arraySupport = PrimitiveArraySupport.getArraySupport(o);
        if (arraySupport != null) {
          arraySupport.appendArray(sb, delim, o);
        } else {
          appendArray(sb, o, delim);
        }
      } else {
        String s = o.toString();
        RedshiftArray.escapeArrayElement(sb, s);
      }
    }
    sb.append('}');
  }

  // Parse a "dirty" integer surrounded by non-numeric characters
  private static int integerPart(String dirtyString) {
    int start = 0;

    while (start < dirtyString.length() && !Character.isDigit(dirtyString.charAt(start))) {
      ++start;
    }

    int end = start;
    while (end < dirtyString.length() && Character.isDigit(dirtyString.charAt(end))) {
      ++end;
    }

    if (start == end) {
      return 0;
    }

    return Integer.parseInt(dirtyString.substring(start, end));
  }

  @Override
  public Statement createStatement(int resultSetType, int resultSetConcurrency,
      int resultSetHoldability) throws SQLException {

    if (RedshiftLogger.isEnable())
    	logger.logFunction(true, resultSetType, resultSetConcurrency, resultSetHoldability);

    checkClosed();
    Statement stmt = new RedshiftStatementImpl(this, resultSetType, resultSetConcurrency, resultSetHoldability);

    if (RedshiftLogger.isEnable())
    	logger.logFunction(false, stmt);

    return stmt;
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
      int resultSetHoldability) throws SQLException {

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true, QuerySanitizer.filterCredentials(sql), resultSetType, resultSetConcurrency, resultSetHoldability);

    checkClosed();

    PreparedStatement pstmt = new RedshiftPreparedStatement(this, sql, resultSetType, resultSetConcurrency,
        resultSetHoldability);

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(false, QuerySanitizer.filterCredentials(pstmt.toString()));

    return pstmt;
  }

  @Override
  public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
      int resultSetHoldability) throws SQLException {

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true, QuerySanitizer.filterCredentials(sql), resultSetType, resultSetConcurrency, resultSetHoldability);

    checkClosed();

    CallableStatement cstmt= new RedshiftCallableStatement(this, sql, resultSetType, resultSetConcurrency,
        resultSetHoldability);

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(false, QuerySanitizer.filterCredentials(cstmt.toString()));

    return cstmt;
  }

  @Override
  public DatabaseMetaData getMetaData() throws SQLException {
    checkClosed();
    if (metadata == null) {
      metadata = new RedshiftDatabaseMetaData(this);
    }
    return metadata;
  }

  @Override
  public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
    setTypeMapImpl(map);
    if(RedshiftLogger.isEnable())
    	logger.log(LogLevel.DEBUG, "  setTypeMap = {0}", map);
  }

  protected Array makeArray(int oid, String fieldString) throws SQLException {
    return new RedshiftArray(this, oid, fieldString);
  }

  protected Blob makeBlob(long oid) throws SQLException {
    return new RedshiftBlob(this, oid);
  }

  protected Clob makeClob(long oid) throws SQLException {
    return new RedshiftClob(this, oid);
  }

  protected SQLXML makeSQLXML() throws SQLException {
    return new RedshiftSQLXML(this);
  }

  @Override
  public Clob createClob() throws SQLException {
    checkClosed();
    throw com.amazon.redshift.Driver.notImplemented(this.getClass(), "createClob()");
  }

  @Override
  public Blob createBlob() throws SQLException {
    checkClosed();
    throw com.amazon.redshift.Driver.notImplemented(this.getClass(), "createBlob()");
  }

  @Override
  public NClob createNClob() throws SQLException {
    checkClosed();
    throw com.amazon.redshift.Driver.notImplemented(this.getClass(), "createNClob()");
  }

  @Override
  public SQLXML createSQLXML() throws SQLException {
    checkClosed();
    return makeSQLXML();
  }

  @Override
  public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
    checkClosed();
    throw com.amazon.redshift.Driver.notImplemented(this.getClass(), "createStruct(String, Object[])");
  }

  @Override
  public Array createArrayOf(String typeName, Object elements) throws SQLException {
    checkClosed();

    final TypeInfo typeInfo = getTypeInfo();

    final int oid = typeInfo.getRSArrayType(typeName);
    final char delim = typeInfo.getArrayDelimiter(oid);

    if (oid == Oid.UNSPECIFIED) {
      throw new RedshiftException(GT.tr("Unable to find server array type for provided name {0}.", typeName),
          RedshiftState.INVALID_NAME);
    }

    if (elements == null) {
      return makeArray(oid, null);
    }

    final String arrayString;

    final PrimitiveArraySupport arraySupport = PrimitiveArraySupport.getArraySupport(elements);

    if (arraySupport != null) {
      // if the oid for the given type matches the default type, we might be
      // able to go straight to binary representation
      if (oid == arraySupport.getDefaultArrayTypeOid(typeInfo) && arraySupport.supportBinaryRepresentation()
          && getPreferQueryMode() != PreferQueryMode.SIMPLE) {
        return new RedshiftArray(this, oid, arraySupport.toBinaryRepresentation(this, elements));
      }
      arrayString = arraySupport.toArrayString(delim, elements);
    } else {
      final Class<?> clazz = elements.getClass();
      if (!clazz.isArray()) {
        throw new RedshiftException(GT.tr("Invalid elements {0}", elements), RedshiftState.INVALID_PARAMETER_TYPE);
      }
      StringBuilder sb = new StringBuilder();
      appendArray(sb, elements, delim);
      arrayString = sb.toString();
    }

    return makeArray(oid, arrayString);
  }

  @Override
  public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
    checkClosed();

    int oid = getTypeInfo().getRSArrayType(typeName);

    if (oid == Oid.UNSPECIFIED) {
      throw new RedshiftException(
          GT.tr("Unable to find server array type for provided name {0}.", typeName),
          RedshiftState.INVALID_NAME);
    }

    if (elements == null) {
      return makeArray(oid, null);
    }

    char delim = getTypeInfo().getArrayDelimiter(oid);
    StringBuilder sb = new StringBuilder();
    appendArray(sb, elements, delim);

    return makeArray(oid, sb.toString());
  }

  @Override
  public boolean isValid(int timeout) throws SQLException {
    if (timeout < 0) {
      throw new RedshiftException(GT.tr("Invalid timeout ({0}<0).", timeout),
          RedshiftState.INVALID_PARAMETER_VALUE);
    }
    if (isClosed()) {
      return false;
    }
    try {
        if (!disableIsValidQuery)
        {
          int savedNetworkTimeOut = getNetworkTimeout();
          try
          {
            setNetworkTimeout(null, timeout * 1000);
            if (replicationConnection)
            {
              Statement statement = createStatement();
              statement.execute("IDENTIFY_SYSTEM");
              statement.close();
            }
            else
            {
              PreparedStatement checkConnectionQuery;
              synchronized (this)
              {
                checkConnectionQuery = prepareStatement("");
              }
              checkConnectionQuery.setQueryTimeout(timeout);
              checkConnectionQuery.executeUpdate();
              checkConnectionQuery.close();
            }
            return true;
          }
          finally
          {
            setNetworkTimeout(null, savedNetworkTimeOut);
          }
        }
        else
            return true;
    } catch (SQLException e) {
      if (RedshiftState.IN_FAILED_SQL_TRANSACTION.getState().equals(e.getSQLState())) {
        // "current transaction aborted", assume the connection is up and running
        return true;
      }

      if(RedshiftLogger.isEnable())
          logger.log(LogLevel.DEBUG, GT.tr("Validating connection."), e);
    }
    return false;
  }

  @Override
  public void setClientInfo(String name, String value) throws SQLClientInfoException {
    try {
      checkClosed();
    } catch (final SQLException cause) {
      Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
      failures.put(name, ClientInfoStatus.REASON_UNKNOWN);
      throw new SQLClientInfoException(GT.tr("This connection has been closed."), failures, cause);
    }

    if ("ApplicationName".equals(name)) // haveMinimumServerVersion(ServerVersion.v9_0) &&
    {
      if (value == null) {
        value = "";
      }
      final String oldValue = queryExecutor.getApplicationName();
      if (value.equals(oldValue)) {
        return;
      }

      try {
        StringBuilder sql = new StringBuilder("SET application_name = '");
        Utils.escapeLiteral(sql, value, getStandardConformingStrings());
        sql.append("'");
        execSQLUpdate(sql.toString());
      } catch (SQLException sqle) {
        Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
        failures.put(name, ClientInfoStatus.REASON_UNKNOWN);
        throw new SQLClientInfoException(
            GT.tr("Failed to set ClientInfo property: {0}", "ApplicationName"), sqle.getSQLState(),
            failures, sqle);
      }
      clientInfo.put(name, value);
      return;
    }

    addWarning(new SQLWarning(GT.tr("ClientInfo property not supported."),
        RedshiftState.NOT_IMPLEMENTED.getState()));
  }

  @Override
  public void setClientInfo(Properties properties) throws SQLClientInfoException {
    try {
      checkClosed();
    } catch (final SQLException cause) {
      Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
      for (Map.Entry<Object, Object> e : properties.entrySet()) {
        failures.put((String) e.getKey(), ClientInfoStatus.REASON_UNKNOWN);
      }
      throw new SQLClientInfoException(GT.tr("This connection has been closed."), failures, cause);
    }

    Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
    for (String name : new String[]{"ApplicationName"}) {
      try {
        setClientInfo(name, properties.getProperty(name, null));
      } catch (SQLClientInfoException e) {
        failures.putAll(e.getFailedProperties());
      }
    }

    if (!failures.isEmpty()) {
      throw new SQLClientInfoException(GT.tr("One or more ClientInfo failed."),
          RedshiftState.NOT_IMPLEMENTED.getState(), failures);
    }
  }

  @Override
  public String getClientInfo(String name) throws SQLException {
    checkClosed();
    clientInfo.put("ApplicationName", queryExecutor.getApplicationName());
    return clientInfo.getProperty(name);
  }

  @Override
  public Properties getClientInfo() throws SQLException {
    checkClosed();
    clientInfo.put("ApplicationName", queryExecutor.getApplicationName());
    return clientInfo;
  }

  public <T> T createQueryObject(Class<T> ifc) throws SQLException {
    checkClosed();
    throw com.amazon.redshift.Driver.notImplemented(this.getClass(), "createQueryObject(Class<T>)");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    checkClosed();
    return iface.isAssignableFrom(getClass());
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    checkClosed();
    if (iface.isAssignableFrom(getClass())) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }

  public String getSchema() throws SQLException {
    checkClosed();
    Statement stmt = createStatement();
    try {
      ResultSet rs = stmt.executeQuery("select current_schema()");
      try {
        if (!rs.next()) {
          return null; // Is it ever possible?
        }
        return rs.getString(1);
      } finally {
        rs.close();
      }
    } finally {
      stmt.close();
    }
  }

  public void setSchema(String schema) throws SQLException {
    if (RedshiftLogger.isEnable())
    	logger.logFunction(true, schema);

  	checkClosed();
    Statement stmt = createStatement();
    try {
      if (schema == null) {
        stmt.executeUpdate("SET SESSION search_path TO DEFAULT");
      } else {
        StringBuilder sb = new StringBuilder();
        sb.append("SET SESSION search_path TO '");
        Utils.escapeLiteral(sb, schema, getStandardConformingStrings());
        sb.append("'");
        stmt.executeUpdate(sb.toString());
        if(RedshiftLogger.isEnable())
        	logger.log(LogLevel.DEBUG, "  setSchema = {0}", schema);
      }
    } finally {
      stmt.close();
    }
  }

  public class AbortCommand implements Runnable {
    public void run() {
      abort();
    }
  }

  public void abort(Executor executor) throws SQLException {

    if (RedshiftLogger.isEnable())
    	logger.logFunction(true, executor);

    if (executor == null) {
      throw new SQLException("executor is null");
    }
    if (isClosed()) {
      if (RedshiftLogger.isEnable())
      	logger.logFunction(false);

      return;
    }

    SQL_PERMISSION_ABORT.checkGuard(this);

    AbortCommand command = new AbortCommand();
    executor.execute(command);

    if (RedshiftLogger.isEnable())
    	logger.logFunction(false);
  }

  public void setNetworkTimeout(Executor executor /*not used*/, int milliseconds) throws SQLException {
  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true, executor, milliseconds);

    checkClosed();

    if (milliseconds < 0) {
      throw new RedshiftException(GT.tr("Network timeout must be a value greater than or equal to 0."),
              RedshiftState.INVALID_PARAMETER_VALUE);
    }

    SecurityManager securityManager = System.getSecurityManager();
    if (securityManager != null) {
      securityManager.checkPermission(SQL_PERMISSION_NETWORK_TIMEOUT);
    }

    try {
      queryExecutor.setNetworkTimeout(milliseconds);
    } catch (IOException ioe) {
      throw new RedshiftException(GT.tr("Unable to set network timeout."),
              RedshiftState.COMMUNICATION_ERROR, ioe);
    }

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(false);

  }

  public int getNetworkTimeout() throws SQLException {
    checkClosed();

    try {
      return queryExecutor.getNetworkTimeout();
    } catch (IOException ioe) {
      throw new RedshiftException(GT.tr("Unable to get network timeout."),
              RedshiftState.COMMUNICATION_ERROR, ioe);
    }
  }

  @Override
  public void setHoldability(int holdability) throws SQLException {
    checkClosed();

    switch (holdability) {
      case ResultSet.CLOSE_CURSORS_AT_COMMIT:
        rsHoldability = holdability;
        break;
      case ResultSet.HOLD_CURSORS_OVER_COMMIT:
        rsHoldability = holdability;
        break;
      default:
        throw new RedshiftException(GT.tr("Unknown ResultSet holdability setting: {0}.", holdability),
            RedshiftState.INVALID_PARAMETER_VALUE);
    }

    if(RedshiftLogger.isEnable())
    	logger.log(LogLevel.DEBUG, "  setHoldability = {0}", holdability);
  }

  @Override
  public int getHoldability() throws SQLException {
    checkClosed();
    return rsHoldability;
  }

  @Override
  public Savepoint setSavepoint() throws SQLException {
    if (RedshiftLogger.isEnable())
    	logger.logFunction(true);

  	checkClosed();

    String pgName;
    if (getAutoCommit()) {
      throw new RedshiftException(GT.tr("Cannot establish a savepoint in auto-commit mode."),
          RedshiftState.NO_ACTIVE_SQL_TRANSACTION);
    }

    RedshiftSavepoint savepoint = new RedshiftSavepoint(savepointId++);
    pgName = savepoint.getRSName();

    // Note we can't use execSQLUpdate because we don't want
    // to suppress BEGIN.
    Statement stmt = createStatement();
    stmt.executeUpdate("SAVEPOINT " + pgName);
    stmt.close();

    return savepoint;
  }

  @Override
  public Savepoint setSavepoint(String name) throws SQLException {
    if (RedshiftLogger.isEnable())
    	logger.logFunction(true, name);

  	checkClosed();

    if (getAutoCommit()) {
      throw new RedshiftException(GT.tr("Cannot establish a savepoint in auto-commit mode."),
          RedshiftState.NO_ACTIVE_SQL_TRANSACTION);
    }

    RedshiftSavepoint savepoint = new RedshiftSavepoint(name);

    // Note we can't use execSQLUpdate because we don't want
    // to suppress BEGIN.
    Statement stmt = createStatement();
    stmt.executeUpdate("SAVEPOINT " + savepoint.getRSName());
    stmt.close();

    return savepoint;
  }

  @Override
  public void rollback(Savepoint savepoint) throws SQLException {
  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true, savepoint);

    checkClosed();

    RedshiftSavepoint pgSavepoint = (RedshiftSavepoint) savepoint;
    execSQLUpdate("ROLLBACK TO SAVEPOINT " + pgSavepoint.getRSName());

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(false);
  }

  @Override
  public void releaseSavepoint(Savepoint savepoint) throws SQLException {
    checkClosed();

    RedshiftSavepoint pgSavepoint = (RedshiftSavepoint) savepoint;
    execSQLUpdate("RELEASE SAVEPOINT " + pgSavepoint.getRSName());
    pgSavepoint.invalidate();
  }

  @Override
  public Statement createStatement(int resultSetType, int resultSetConcurrency)
      throws SQLException {

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true, resultSetType, resultSetConcurrency);

    checkClosed();
    Statement stmt = createStatement(resultSetType, resultSetConcurrency, getHoldability());

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(false, stmt);

  	return stmt;
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true, QuerySanitizer.filterCredentials(sql), resultSetType, resultSetConcurrency);

    checkClosed();
    PreparedStatement pstmt = prepareStatement(sql, resultSetType, resultSetConcurrency, getHoldability());

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(false, QuerySanitizer.filterCredentials(pstmt.toString()));

    return pstmt;
  }

  @Override
  public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true, QuerySanitizer.filterCredentials(sql), resultSetType, resultSetConcurrency);

    checkClosed();
    CallableStatement cstmt = prepareCall(sql, resultSetType, resultSetConcurrency, getHoldability());

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(false, QuerySanitizer.filterCredentials(cstmt.toString()));

    return cstmt;
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {

  	PreparedStatement pstmt;

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true, QuerySanitizer.filterCredentials(sql), autoGeneratedKeys);

    if (autoGeneratedKeys != Statement.RETURN_GENERATED_KEYS) {
    	pstmt = prepareStatement(sql);
    }
    else {
    	pstmt = prepareStatement(sql, (String[]) null);
    	((RedshiftPreparedStatement)pstmt).setAutoGeneratedKeys(autoGeneratedKeys);
    }

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(false, QuerySanitizer.filterCredentials(pstmt.toString()));

    return pstmt;
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true, QuerySanitizer.filterCredentials(sql), columnIndexes);

    if (columnIndexes == null
    		|| columnIndexes.length == 0) {
    	PreparedStatement pstmt = prepareStatement(sql);

    	if (RedshiftLogger.isEnable())
      	logger.logFunction(false, pstmt);

      return pstmt;
    }

    checkClosed();
    throw new RedshiftException(GT.tr("Returning autogenerated keys is not supported."),
        RedshiftState.NOT_IMPLEMENTED);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
  	PreparedStatement pstmt;

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(true, QuerySanitizer.filterCredentials(sql), columnNames);

    if (columnNames == null
    		 || columnNames.length == 0) {
    	pstmt = prepareStatement(sql);
    }
    else {
//      throw new RedshiftException(GT.tr("Returning autogenerated keys by column name is not supported."),
//          RedshiftState.NOT_IMPLEMENTED);

	    CachedQuery cachedQuery = borrowReturningQuery(sql, columnNames);
	    RedshiftPreparedStatement ps =
	        new RedshiftPreparedStatement(this, cachedQuery,
	            ResultSet.TYPE_FORWARD_ONLY,
	            ResultSet.CONCUR_READ_ONLY,
	            getHoldability());
	    Query query = cachedQuery.query;
	    SqlCommand sqlCommand = query.getSqlCommand();
	    if (sqlCommand != null) {
	      ps.wantsGeneratedKeysAlways = sqlCommand.isReturningKeywordPresent();
	    } else {
	      // If composite query is given, just ignore "generated keys" arguments
	    }
	    pstmt = ps;
    }

  	if (RedshiftLogger.isEnable())
    	logger.logFunction(false, QuerySanitizer.filterCredentials(pstmt.toString()));

    return pstmt;
  }

  @Override
  public final Map<String,String> getParameterStatuses() {
    return queryExecutor.getParameterStatuses();
  }

  @Override
  public final String getParameterStatus(String parameterName) {
    return queryExecutor.getParameterStatus(parameterName);
  }

  /**
   * Get the optional setting.
   *
   * @param key                       The name of the setting to retrieve.
   * @param info                			The connection settings generated by a call to
   *                                  UpdateConnectionSettings().
   *
   * @return The String representing the specified setting, or null if the setting isn't present.
   */
  public static String getOptionalSetting(String key, Properties info)
  {
      return info.getProperty(key);
  }

  public static String getOptionalConnSetting(String key, Properties info) {
  	return getOptionalSetting(key, info);
  }

  /**
   * Get the required setting, and throw an exception if it isn't present.
   *
   * @param key                       The name of the setting to retrieve.
   * @param info                			The connection settings generated by a call to
   *                                  UpdateConnectionSettings().
   *
   * @return The Variant representing the specified setting.
   *
   * @throws RedshiftException         If the required setting isn't present.
   */
  public static String getRequiredSetting(String key, Properties info)
      throws RedshiftException
  {
      String setting = info.getProperty(key);

      if (null == setting)
      {
        throw new RedshiftException(
        		GT.tr("The required connection property does not found {0}", key),
        		RedshiftState.UNEXPECTED_ERROR);
      }

      return setting;
  }

  public static String getRequiredConnSetting(String key, Properties info)
      throws RedshiftException
  {
  	return getRequiredSetting(key, info);
  }

  /**
   * Helper function to break out AuthMech setting logic which is overly complicated in order to
   * remain backwards compatible with earlier releases, and add the "sslmode" feature.
   *
   * @param info                Redshift settings used to authenticate if connection
   *                                  should be granted.
   *
   * @throws RedshiftException        If an unspecified error occurs.
   */
  private boolean setAuthMech(Properties info) throws RedshiftException
  {
      //If key word ssl is specified in connection string either with nothing or true,
      //SSL is set to be required.
      boolean sslExplicitlyEnabled = false;
      boolean sslExplicitlyDisabled = false;
      String ssl = getOptionalSetting(RedshiftProperty.SSL.getName(), info);
      if (null != ssl)
      {
          if (Boolean.parseBoolean(ssl) || ssl.equals(""))
          {
              sslExplicitlyEnabled = true;
              m_settings.m_authMech = AuthMech.VERIFY_CA;
          }
          else if (!Boolean.parseBoolean(ssl))
          {
              sslExplicitlyDisabled = true;
          }
      }

      String sslFactory = getOptionalSetting(RedshiftProperty.SSL_FACTORY.getName(), info);
      boolean sslFactorySet = false;

      // older releases would take sslfactory setting as a trigger to enable SSL.
      if ((null != sslFactory) &&
          (isNonValidationFactory(sslFactory)))
      {
          // decrease authmech from "VERIFY_CA" to "REQUIRE"
          sslFactorySet = true;
          m_settings.m_authMech = AuthMech.REQUIRE;
      }

      String sslModeProp = getOptionalSetting(RedshiftProperty.SSL_MODE.getName(), info);
      String authMechProp = getOptionalSetting(RedshiftProperty.AUTH_MECH.getName(), info);
      String sslMode = (sslModeProp != null) ? sslModeProp : authMechProp;

      boolean sslModeSet = false;
      if (null != sslMode)
      {
          sslModeSet = true;
      }

      if (sslModeSet)
      {
          // SSL is now set to true by default. This should only fail if someone has explicitly
          // disabled SSL.
          if (sslExplicitlyDisabled)
          {
          	throw new RedshiftException(GT.tr("Conflict in connection property setting {0} and {1}",
          																		RedshiftProperty.SSL_MODE.getName(),
          																		RedshiftProperty.SSL.getName()),
          															RedshiftState.UNEXPECTED_ERROR);
          }

          if (sslFactorySet)
          {
          	throw new RedshiftException(GT.tr("Conflict in connection property setting {0} and {1}",
								RedshiftProperty.SSL_MODE.getName(),
								RedshiftProperty.SSL_FACTORY.getName()),
          		RedshiftState.UNEXPECTED_ERROR);

          }

          if (sslMode.equalsIgnoreCase(SslMode.VERIFY_FULL.value))
          {
              // The user specifically asked for hostname validation
              m_settings.m_authMech = AuthMech.VERIFY_FULL;
          }
          else if (sslMode.equalsIgnoreCase(SslMode.VERIFY_CA.value))
          {
              // By default, if is ssl is enabled, the server hostname validation
              // is not enabled.
              m_settings.m_authMech = AuthMech.VERIFY_CA;
          }
          else
          {
          	RedshiftException err =  new RedshiftException(GT.tr("Invalid connection property value {0} : {1}",
								RedshiftProperty.SSL_MODE.getName(),
								sslMode),
							RedshiftState.UNEXPECTED_ERROR);

            if(RedshiftLogger.isEnable())
          		logger.log(LogLevel.ERROR, err.toString());

          	throw err;
          }
      }

      // If none of above is set, default to enable SSL
      if (!sslExplicitlyEnabled && !sslExplicitlyDisabled && !sslFactorySet && !sslModeSet)
      {
          m_settings.m_authMech = AuthMech.VERIFY_CA;
      }

      return sslExplicitlyDisabled;
  }

  /**
   * Returns true if the given factory is non validating. False otherwise.
   *
   * @param factory                   The factory.
   *
   * @return true if the given factory is non validating. False otherwise.
   */
  private boolean isNonValidationFactory(String factory)
  {
      boolean result = false;

      // The valid non validating factory names are the one in the driver or the legacy one
      if (factory.equals(NON_VALIDATING_SSL_FACTORY) ||
          factory.equals(NonValidatingFactory.class.getName()))
      {
          result = true;
      }

      return result;
  }

  /**
   * Tries to find whether the JVM is 64bit or not.
   * If it returns true, the JVM can be assumed to be 64-bit.
   * If it returns false, the JVM can be assumed to be 32-bit.
   * Returns true (i.e. 64-bit) by default, if it is not able to find the bitness of the JVM.
   *
   * @return true if it is 64-bit JVM, false if it is 32-bit JVM
   */
  private static boolean checkIs64bitJVM() {
    String bitness = System.getProperty("sun.arch.data.model");
    if (bitness != null && bitness.contains("32")) {
      return false;
    }
    // in other cases we can't conclude if its 32-bit JVM, hence assume 64-bit
    return true;
  }

}
