package org.kegbot.api;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.util.List;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.HttpParams;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.kegbot.kegtap.Utils;
import org.kegbot.proto.Api.DrinkDetail;
import org.kegbot.proto.Api.DrinkDetailHtmlSet;
import org.kegbot.proto.Api.DrinkSet;
import org.kegbot.proto.Api.KegDetail;
import org.kegbot.proto.Api.KegSet;
import org.kegbot.proto.Api.RecordDrinkRequest;
import org.kegbot.proto.Api.RecordTemperatureRequest;
import org.kegbot.proto.Api.SessionDetail;
import org.kegbot.proto.Api.SessionSet;
import org.kegbot.proto.Api.SoundEventSet;
import org.kegbot.proto.Api.SystemEventDetailSet;
import org.kegbot.proto.Api.SystemEventHtmlSet;
import org.kegbot.proto.Api.TapDetail;
import org.kegbot.proto.Api.TapDetailSet;
import org.kegbot.proto.Api.ThermoLogSet;
import org.kegbot.proto.Api.ThermoSensorSet;
import org.kegbot.proto.Api.UserDetailSet;
import org.kegbot.proto.Models.AuthenticationToken;
import org.kegbot.proto.Models.Drink;
import org.kegbot.proto.Models.ThermoLog;
import org.kegbot.proto.Models.User;

import android.os.SystemClock;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.Message;
import com.google.protobuf.Message.Builder;

public class KegbotApiImpl implements KegbotApi {

  private static KegbotApiImpl sSingleton = null;

  private final HttpClient mHttpClient;
  private String mBaseUrl;
  private String mUsername = "";
  private String mPassword = "";

  private String apiKey = null;

  private long mLastApiAttemptUptimeMillis = -1;
  private long mLastApiSuccessUptimeMillis = -1;
  private long mLastApiFailureUptimeMillis = -1;

  public static interface Listener {
    public void debug(String message);
  }

  private Listener mListener = null;

  private KegbotApiImpl() {
    mHttpClient = getThreadSafeClient();
    mBaseUrl = "http://localhost/";
  }

  public synchronized void setListener(Listener listener) {
    mListener = listener;
  }

  private void noteApiAttempt() {
    mLastApiAttemptUptimeMillis = SystemClock.uptimeMillis();
  }

  private void noteApiSuccess() {
    mLastApiSuccessUptimeMillis = SystemClock.uptimeMillis();
  }

  private void noteApiFailure() {
    mLastApiFailureUptimeMillis = SystemClock.uptimeMillis();
  }

  private JsonNode toJson(HttpResponse response) throws KegbotApiException {
    final Header header = response.getFirstHeader("Content-type");
    if (header == null) {
      throw new KegbotApiServerError("No content-type header.");
    }
    final String contentType = header.getValue();
    if (!"application/json".equals(contentType)) {
      throw new KegbotApiServerError("Unknown content-type: " + contentType);
    }
    try {
      final ObjectMapper mapper = new ObjectMapper();
      final JsonNode rootNode = mapper.readValue(response.getEntity().getContent(), JsonNode.class);
      return rootNode;
    } catch (IOException e) {
      throw new KegbotApiException(e);
    }
  }

  private String getRequestUrl(String path) {
    return mBaseUrl + path;
  }

  private JsonNode doGet(String path) throws KegbotApiException {
    return doGet(path, null);
  }

  private JsonNode doGet(String path, List<NameValuePair> params) throws KegbotApiException {
    final List<NameValuePair> actualParams = Lists.newArrayList();
    if (params != null) {
      actualParams.addAll(params);
    }
    if (!Strings.isNullOrEmpty(apiKey)) {
      actualParams.add(new BasicNameValuePair("api_key", apiKey));
    }
    if (!actualParams.isEmpty()) {
      path += "?" + URLEncodedUtils.format(actualParams, "utf-8");
    }

    final HttpGet request = new HttpGet(getRequestUrl(path));
    debug("GET: uri=" + request.getURI());
    debug("GET: request=" + request.getRequestLine());
    return toJson(execute(request));
  }

  private JsonNode doPost(String path, Map<String, String> params) throws KegbotApiException {
    if (apiKey != null) {
      params.put("api_key", apiKey);
    }
    return toJson(doRawPost(path, params));
  }

  private HttpResponse doRawPost(String path, Map<String, String> params) throws KegbotApiException {
    HttpPost request = new HttpPost(getRequestUrl(path));
    debug("POST: uri=" + request.getURI());
    debug("POST: request=" + request.getRequestLine());
    List<NameValuePair> pairs = Lists.newArrayList();
    for (Map.Entry<String, String> entry : params.entrySet()) {
      pairs.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
    }
    try {
      request.setEntity(new UrlEncodedFormEntity(pairs));
    } catch (UnsupportedEncodingException e) {
      throw new KegbotApiException(e);
    }
    return execute(request);
  }

  public static DefaultHttpClient getThreadSafeClient() {
    DefaultHttpClient client = new DefaultHttpClient();
    ClientConnectionManager mgr = client.getConnectionManager();
    HttpParams params = client.getParams();
    client = new DefaultHttpClient(
        new ThreadSafeClientConnManager(params, mgr.getSchemeRegistry()), params);
    return client;
  }

  private HttpResponse execute(HttpUriRequest request) throws KegbotApiException {
    noteApiAttempt();
    boolean success = false;
    try {
      final HttpResponse response;
      synchronized (mHttpClient) {
        debug("Requesting: " + request.getURI());
        response = mHttpClient.execute(request);
        debug("DONE: " + request.getURI());
        debug(response.getStatusLine().toString());
      }
      final int statusCode = response.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_OK) {
        final String reason = response.getStatusLine().getReasonPhrase();
        final String message = "Error fetching " + request.getURI() + ": statusCode=" + statusCode
            + ", reason=" + reason;

        switch (statusCode) {
          case HttpStatus.SC_NOT_FOUND:
            throw new KegbotApiNotFoundError(message);
          default:
            throw new KegbotApiServerError(message);
        }
      }
      success = true;
      return response;
    } catch (ClientProtocolException e) {
      throw new KegbotApiException(e);
    } catch (IOException e) {
      throw new KegbotApiException(e);
    } finally {
      if (success) {
        noteApiSuccess();
      } else {
        noteApiFailure();
      }
    }
  }

  private JsonNode getJson(String path) throws KegbotApiException {
    JsonNode root = doGet(path);
    if (!root.has("result")) {
      throw new KegbotApiServerError("No result from server!");
    }
    return root.get("result");
  }

  private JsonNode getJson(String path, List<NameValuePair> params) throws KegbotApiException {
    JsonNode root = doGet(path, params);
    if (!root.has("result")) {
      throw new KegbotApiServerError("No result from server!");
    }
    return root.get("result");
  }

  private Message getProto(String path, Builder builder) throws KegbotApiException {
    return ProtoEncoder.toProto(builder, getJson(path)).build();
  }

  private Message getProto(String path, Builder builder, List<NameValuePair> params) throws KegbotApiException {
    return ProtoEncoder.toProto(builder, getJson(path, params)).build();
  }

  private JsonNode postJson(String path, Map<String, String> params) throws KegbotApiException {
    JsonNode root = doPost(path, params);
    if (!root.has("result")) {
      throw new KegbotApiServerError("No result from server!");
    }
    return root.get("result");
  }

  private Message postProto(String path, Builder builder, Map<String, String> params)
      throws KegbotApiException {
    return ProtoEncoder.toProto(builder, postJson(path, params)).build();
  }

  @Override
  public boolean setAccountCredentials(String username, String password) {
    this.mUsername = username;
    this.mPassword = password;
    return true;
  }

  @Override
  public void setApiUrl(String apiUrl) {
    mBaseUrl = apiUrl;
  }

  @Override
  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
    debug("Set apiKey=" + apiKey);
  }

  private void login() throws KegbotApiException {
    if (apiKey == null || apiKey.isEmpty()) {
      Map<String, String> params = Maps.newLinkedHashMap();
      params.put("username", mUsername);
      params.put("password", mPassword);
      // TODO: removeme!
      debug("Logging in as username=" + mUsername + " password=" + mPassword);
      doPost("/login/", params);

      // Made it this far -- login succeeded.
      JsonNode result = getJson("/get-api-key/");
      final JsonNode keyNode = result.get("api_key");
      if (keyNode != null) {
        debug("Got api key:" + keyNode.getValueAsText());
        setApiKey(keyNode.getValueAsText());
      }
    }
    /*
     * final int statusCode = response.getStatusLine().getStatusCode(); throw
     * new IllegalStateException("Got status code: " + statusCode);
     */
  }

  @Override
  public KegSet getAllKegs() throws KegbotApiException {
    return (KegSet) getProto("/kegs/", KegSet.newBuilder());
  }

  @Override
  public SoundEventSet getAllSoundEvents() throws KegbotApiException {
    return (SoundEventSet) getProto("/sound-events/", SoundEventSet.newBuilder());
  }

  @Override
  public TapDetailSet getAllTaps() throws KegbotApiException {
    return (TapDetailSet) getProto("/taps/", TapDetailSet.newBuilder());
  }

  @Override
  public AuthenticationToken getAuthToken(String authDevice, String tokenValue)
      throws KegbotApiException {
    return (AuthenticationToken) getProto("/auth-tokens/" + authDevice + "." + tokenValue + "/",
        AuthenticationToken.newBuilder());
  }

  @Override
  public DrinkDetail getDrinkDetail(String id) throws KegbotApiException {
    return (DrinkDetail) getProto("/drinks/" + id, DrinkDetail.newBuilder());
  }

  @Override
  public KegDetail getKegDetail(String id) throws KegbotApiException {
    return (KegDetail) getProto("/kegs/" + id, KegDetail.newBuilder());
  }

  @Override
  public DrinkSet getKegDrinks(String kegId) throws KegbotApiException {
    return (DrinkSet) getProto("/kegs/" + kegId + "/drinks/", DrinkSet.newBuilder());
  }

  @Override
  public SystemEventDetailSet getKegEvents(String kegId) throws KegbotApiException {
    return (SystemEventDetailSet) getProto("/keg/" + kegId + "/events/", SystemEventDetailSet
        .newBuilder());
  }

  @Override
  public SessionSet getKegSessions(String kegId) throws KegbotApiException {
    return (SessionSet) getProto("/kegs/" + kegId + "/sessions/", SessionSet.newBuilder());
  }

  @Override
  public String getLastDrinkId() throws KegbotApiException {
    JsonNode root = getJson("/last-drink-id/");
    return root.get("id").getTextValue();
  }

  @Override
  public DrinkSet getRecentDrinks() throws KegbotApiException {
    return (DrinkSet) getProto("/last-drinks/", DrinkSet.newBuilder());
  }

  @Override
  public DrinkDetailHtmlSet getRecentDrinksHtml() throws KegbotApiException {
    return (DrinkDetailHtmlSet) getProto("/last-drinks-html/", DrinkDetailHtmlSet.newBuilder());
  }

  @Override
  public SystemEventDetailSet getRecentEvents() throws KegbotApiException {
    return (SystemEventDetailSet) getProto("/events/", SystemEventDetailSet.newBuilder());
  }

  @Override
  public SystemEventDetailSet getRecentEvents(final long sinceEventId) throws KegbotApiException {
    final List<NameValuePair> params = Lists.newArrayList();
    params.add(new BasicNameValuePair("since", String.valueOf(sinceEventId)));
    return (SystemEventDetailSet) getProto("/events/", SystemEventDetailSet.newBuilder(), params);
  }

  @Override
  public SystemEventHtmlSet getRecentEventsHtml() throws KegbotApiException {
    return (SystemEventHtmlSet) getProto("/events/html/", SystemEventHtmlSet.newBuilder());
  }

  @Override
  public SessionDetail getSessionDetail(String id) throws KegbotApiException {
    return (SessionDetail) getProto("/sessions/" + id, SessionDetail.newBuilder());
  }

  @Override
  public TapDetail getTapDetail(String tapName) throws KegbotApiException {
    return (TapDetail) getProto("/taps/" + tapName, TapDetail.newBuilder());
  }

  @Override
  public ThermoLogSet getThermoSensorLogs(String sensorId) throws KegbotApiException {
    return (ThermoLogSet) getProto("/thermo-sensors/" + sensorId + "/logs/", ThermoLogSet
        .newBuilder());
  }

  @Override
  public ThermoSensorSet getThermoSensors() throws KegbotApiException {
    return (ThermoSensorSet) getProto("/thermo-sensors/", ThermoSensorSet.newBuilder());
  }

  @Override
  public User getUser(String username) throws KegbotApiException {
    return (User) getProto("/users/" + username, TapDetail.newBuilder());
  }

  @Override
  public DrinkSet getUserDrinks(String username) throws KegbotApiException {
    return (DrinkSet) getProto("/users/" + username + "/drinks/", DrinkSet.newBuilder());
  }

  @Override
  public SystemEventDetailSet getUserEvents(String username) throws KegbotApiException {
    return (SystemEventDetailSet) getProto("/users/" + username + "/events/", SystemEventDetailSet
        .newBuilder());
  }

  @Override
  public UserDetailSet getUsers() throws KegbotApiException {
    login();
    return (UserDetailSet) getProto("/users/", UserDetailSet.newBuilder());
  }

  @Override
  public SessionSet getCurrentSessions() throws KegbotApiException {
    return (SessionSet) getProto("/sessions/current/", SessionSet.newBuilder());
  }

  @Override
  public Drink recordDrink(final RecordDrinkRequest request) throws KegbotApiException {
    if (!request.isInitialized()) {
      throw new KegbotApiException("Request is missing required field(s)");
    }

    final Map<String, String> params = Maps.newLinkedHashMap();

    final String tapName = request.getTapName();
    final int ticks = request.getTicks();

    params.put("ticks", String.valueOf(ticks));

    final float volumeMl = request.getVolumeMl();
    if (volumeMl > 0) {
      params.put("volume_ml", String.valueOf(volumeMl));
    }

    final String username = request.getUsername();
    if (!Strings.isNullOrEmpty(username)) {
      params.put("username", username);
    }

    final String recordDate = request.getRecordDate();
    boolean haveDate = false;
    if (!Strings.isNullOrEmpty(recordDate)) {
      try {
        params.put("record_date", recordDate); // new API
        haveDate = true;
        final long pourTime = Utils.dateFromIso8601String(recordDate);
        params.put("pour_time", String.valueOf(pourTime)); // old API
      } catch (ParseException e) {
        // Ignore.
      }
    }

    final int secondsAgo = request.getSecondsAgo();
    if (!haveDate & secondsAgo > 0) {
      params.put("seconds_ago", String.valueOf(secondsAgo));
    }

    final int durationSeconds = request.getDurationSeconds();
    if (durationSeconds > 0) {
      params.put("duration_seconds", String.valueOf(durationSeconds));
    }

    final boolean spilled = request.getSpilled();
    if (spilled) {
      params.put("spilled", String.valueOf(spilled));
    }
    login();
    return (Drink) postProto("/taps/" + tapName, Drink.newBuilder(), params);
  }

  @Override
  public ThermoLog recordTemperature(final RecordTemperatureRequest request)
      throws KegbotApiException {
    if (!request.isInitialized()) {
      throw new KegbotApiException("Request is missing required field(s)");
    }

    final String sensorName = "kegboard." + request.getSensorName();
    final String sensorValue = String.valueOf(request.getTempC());

    final Map<String, String> params = Maps.newLinkedHashMap();
    params.put("temp_c", sensorValue);
    login();
    return (ThermoLog) postProto("/thermo-sensors/" + sensorName, ThermoLog.newBuilder(), params);
  }

  private synchronized void debug(String message) {
    if (mListener != null) {
      mListener.debug(message);
    }
  }

  public static synchronized KegbotApiImpl getSingletonInstance() {
    if (sSingleton == null) {
      sSingleton = new KegbotApiImpl();
    }
    return sSingleton;
  }
}