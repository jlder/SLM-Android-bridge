import { createServer } from "node:http";
import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const DEFAULT_MAX_UPLOAD_BYTES = 20 * 1024 * 1024;
const DRIVE_FILES = "https://www.googleapis.com/drive/v3/files";
const DRIVE_UPLOADS = "https://www.googleapis.com/upload/drive/v3/files";

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export function normalizeRegistration(value) {
  const result = String(value ?? "").trim().toUpperCase();
  if (!/^[A-Z0-9][A-Z0-9-]{1,15}$/.test(result)) {
    throw new HttpError(400, "Invalid glider registration");
  }
  return result;
}

function safeFilename(value) {
  const result = String(value ?? "").trim();
  if (!result || result.length > 160 || /[\\/\0\r\n]/.test(result) || result.includes("..")) {
    throw new HttpError(400, "Invalid filename");
  }
  return result;
}

function getBoundary(contentType) {
  const match = /(?:^|;)\s*boundary=(?:"([^"]+)"|([^;\s]+))/i.exec(contentType ?? "");
  const boundary = match?.[1] ?? match?.[2] ?? "";
  if (!boundary || boundary.length > 70 || /[^\x20-\x7e]/.test(boundary)) {
    throw new HttpError(400, "Invalid multipart boundary");
  }
  return boundary;
}

function dispositionParameter(disposition, parameter) {
  const expression = new RegExp(`(?:^|;)\\s*${parameter}=(?:"([^"]*)"|([^;\\r\\n]*))`, "i");
  const match = expression.exec(disposition);
  return match ? (match[1] ?? match[2] ?? "").trim() : undefined;
}

export function parseMultipartFile(contentType, body) {
  const boundary = getBoundary(contentType);
  const firstMarker = Buffer.from(`--${boundary}\r\n`, "ascii");
  const nextMarker = Buffer.from(`\r\n--${boundary}`, "ascii");
  if (!body.subarray(0, firstMarker.length).equals(firstMarker)) {
    throw new HttpError(400, "Malformed multipart body");
  }

  let cursor = firstMarker.length;
  while (cursor < body.length) {
    const headerEnd = body.indexOf("\r\n\r\n", cursor, "ascii");
    if (headerEnd < 0) throw new HttpError(400, "Malformed multipart headers");
    const headers = body.toString("utf8", cursor, headerEnd);
    const dataStart = headerEnd + 4;
    const dataEnd = body.indexOf(nextMarker, dataStart);
    if (dataEnd < 0) throw new HttpError(400, "Malformed multipart data");

    const disposition = /^content-disposition:\s*form-data;[^\r\n]*$/im.exec(headers)?.[0] ?? "";
    const name = dispositionParameter(disposition, "name") ?? "";
    const filename = dispositionParameter(disposition, "filename");
    if (name === "file" && filename !== undefined) {
      return { filename: safeFilename(filename), data: body.subarray(dataStart, dataEnd) };
    }

    cursor = dataEnd + nextMarker.length;
    if (body.subarray(cursor, cursor + 2).toString("ascii") === "--") break;
    if (body.subarray(cursor, cursor + 2).toString("ascii") !== "\r\n") {
      throw new HttpError(400, "Malformed multipart separator");
    }
    cursor += 2;
  }
  throw new HttpError(400, "Multipart field 'file' is required");
}

function constantTimeMatches(actual, expected) {
  const left = Buffer.from(actual ?? "", "utf8");
  const right = Buffer.from(expected ?? "", "utf8");
  return left.length === right.length && timingSafeEqual(left, right);
}

function readRequiredFile(path, label) {
  try {
    return readFileSync(path, "utf8").trim();
  } catch {
    throw new Error(`${label} could not be read from ${path}`);
  }
}

function loadConfiguration(env = process.env) {
  const defaultCredential = env.LOCALAPPDATA
    ? join(env.LOCALAPPDATA, "SLMUploadGateway", "oauth.json")
    : "";
  const oauthText = env.SLM_OAUTH_JSON || readRequiredFile(
    env.SLM_OAUTH_FILE || defaultCredential,
    "OAuth credential"
  );
  let oauth;
  try {
    oauth = JSON.parse(oauthText.replace(/^\uFEFF/, ""));
  } catch {
    throw new Error("OAuth credential is not valid JSON");
  }
  for (const key of ["client_id", "client_secret", "token_uri", "refresh_token", "root_folder_id"]) {
    if (!oauth[key]) throw new Error(`OAuth credential is missing ${key}`);
  }

  const credentialPath = env.SLM_OAUTH_FILE || defaultCredential;
  const tokenFile = env.SLM_UPLOAD_TOKEN_FILE || (credentialPath ? join(dirname(credentialPath), "upload-token.txt") : "");
  const uploadToken = (env.SLM_UPLOAD_TOKEN || readRequiredFile(tokenFile, "Upload token")).trim();
  if (uploadToken.length < 32) throw new Error("SLM upload token must contain at least 32 characters");

  const maxUploadBytes = Number(env.SLM_MAX_UPLOAD_BYTES || DEFAULT_MAX_UPLOAD_BYTES);
  if (!Number.isSafeInteger(maxUploadBytes) || maxUploadBytes < 1024 || maxUploadBytes > 100 * 1024 * 1024) {
    throw new Error("SLM_MAX_UPLOAD_BYTES is invalid");
  }
  return { oauth, uploadToken, maxUploadBytes };
}

function escapeDriveQuery(value) {
  return String(value).replaceAll("\\", "\\\\").replaceAll("'", "\\'");
}

class DriveClient {
  constructor(oauth, fetchImpl = fetch) {
    this.oauth = oauth;
    this.fetch = fetchImpl;
    this.accessToken = "";
    this.accessTokenExpiresAt = 0;
    this.registrationFolders = new Map();
  }

  async token() {
    if (this.accessToken && Date.now() < this.accessTokenExpiresAt) return this.accessToken;
    const response = await this.fetch(this.oauth.token_uri, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        client_id: this.oauth.client_id,
        client_secret: this.oauth.client_secret,
        refresh_token: this.oauth.refresh_token,
        grant_type: "refresh_token"
      })
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok || !result.access_token) {
      throw new Error(`Google token refresh failed (${response.status})`);
    }
    this.accessToken = result.access_token;
    const lifetime = Math.max(60, Number(result.expires_in) || 3600);
    this.accessTokenExpiresAt = Date.now() + (lifetime - 30) * 1000;
    return this.accessToken;
  }

  async authorizedFetch(url, options = {}, retry = true) {
    const token = await this.token();
    const response = await this.fetch(url, {
      ...options,
      headers: { ...(options.headers || {}), authorization: `Bearer ${token}` }
    });
    if (response.status === 401 && retry) {
      this.accessToken = "";
      this.accessTokenExpiresAt = 0;
      return this.authorizedFetch(url, options, false);
    }
    return response;
  }

  async json(url, options, operation) {
    const response = await this.authorizedFetch(url, options);
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(`${operation} failed (${response.status})`);
    return result;
  }

  async registrationFolder(registration) {
    if (this.registrationFolders.has(registration)) return this.registrationFolders.get(registration);
    const q = [
      `'${escapeDriveQuery(this.oauth.root_folder_id)}' in parents`,
      `name = '${escapeDriveQuery(registration)}'`,
      "mimeType = 'application/vnd.google-apps.folder'",
      "trashed = false"
    ].join(" and ");
    const search = new URL(DRIVE_FILES);
    search.searchParams.set("spaces", "drive");
    search.searchParams.set("pageSize", "2");
    search.searchParams.set("fields", "files(id,name)");
    search.searchParams.set("q", q);
    const found = await this.json(search, { method: "GET" }, "Drive folder lookup");
    let folderId = found.files?.[0]?.id;
    if (!folderId) {
      const created = await this.json(`${DRIVE_FILES}?fields=id,name`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          name: registration,
          mimeType: "application/vnd.google-apps.folder",
          parents: [this.oauth.root_folder_id],
          appProperties: { slmRegistration: registration }
        })
      }, "Drive folder creation");
      folderId = created.id;
    }
    if (!folderId) throw new Error("Google Drive did not return a registration folder ID");
    this.registrationFolders.set(registration, folderId);
    return folderId;
  }

  async duplicate(folderId, sha256) {
    const q = [
      `'${escapeDriveQuery(folderId)}' in parents`,
      "trashed = false",
      `appProperties has { key='sha256' and value='${escapeDriveQuery(sha256)}' }`
    ].join(" and ");
    const search = new URL(DRIVE_FILES);
    search.searchParams.set("spaces", "drive");
    search.searchParams.set("pageSize", "1");
    search.searchParams.set("fields", "files(id,name,webViewLink)");
    search.searchParams.set("q", q);
    const result = await this.json(search, { method: "GET" }, "Drive duplicate lookup");
    return result.files?.[0] ?? null;
  }

  async upload(registration, filename, data, sha256) {
    const folderId = await this.registrationFolder(registration);
    const existing = await this.duplicate(folderId, sha256);
    if (existing) return { ...existing, duplicate: true };

    const session = await this.authorizedFetch(`${DRIVE_UPLOADS}?uploadType=resumable&fields=id,name,webViewLink`, {
      method: "POST",
      headers: {
        "content-type": "application/json; charset=utf-8",
        "x-upload-content-type": "application/octet-stream",
        "x-upload-content-length": String(data.length)
      },
      body: JSON.stringify({
        name: filename,
        parents: [folderId],
        appProperties: { sha256, slmRegistration: registration, source: "slm-android" }
      })
    });
    if (!session.ok) throw new Error(`Drive upload session creation failed (${session.status})`);
    const location = session.headers.get("location");
    if (!location) throw new Error("Drive upload session did not return a location");

    const uploaded = await this.authorizedFetch(location, {
      method: "PUT",
      headers: { "content-type": "application/octet-stream", "content-length": String(data.length) },
      body: data
    });
    const result = await uploaded.json().catch(() => ({}));
    if (!uploaded.ok || !result.id) throw new Error(`Drive file upload failed (${uploaded.status})`);
    return { ...result, duplicate: false };
  }
}

async function readBody(request, maximum) {
  const declared = Number(request.headers["content-length"] || 0);
  if (declared > maximum) throw new HttpError(413, "Upload is too large");
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > maximum) throw new HttpError(413, "Upload is too large");
    chunks.push(chunk);
  }
  return Buffer.concat(chunks, length);
}

function sendJson(response, status, value) {
  const body = Buffer.from(JSON.stringify(value), "utf8");
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": body.length,
    "cache-control": "no-store",
    "x-content-type-options": "nosniff"
  });
  response.end(body);
}

export function createGateway(configuration, drive = new DriveClient(configuration.oauth)) {
  return createServer(async (request, response) => {
    const requestId = randomUUID();
    try {
      const url = new URL(request.url, "http://gateway.local");
      if (request.method === "GET" && url.pathname === "/healthz") {
        sendJson(response, 200, { status: "ok" });
        return;
      }
      if (request.method !== "POST" || url.pathname !== "/v1/uploads") {
        throw new HttpError(404, "Not found");
      }

      const authorization = request.headers.authorization ?? "";
      const bearer = authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
      if (!constantTimeMatches(bearer, configuration.uploadToken)) throw new HttpError(401, "Unauthorized");
      const registration = normalizeRegistration(request.headers["x-slm-registration"]);
      const body = await readBody(request, configuration.maxUploadBytes + 16 * 1024);
      const file = parseMultipartFile(request.headers["content-type"], body);
      if (!file.data.length) throw new HttpError(400, "The uploaded file is empty");
      if (file.data.length > configuration.maxUploadBytes) throw new HttpError(413, "Upload is too large");
      const sha256 = createHash("sha256").update(file.data).digest("hex");
      const result = await drive.upload(registration, file.filename, file.data, sha256);
      sendJson(response, result.duplicate ? 200 : 201, {
        requestId,
        status: result.duplicate ? "duplicate" : "uploaded",
        registration,
        filename: file.filename,
        size: file.data.length,
        sha256,
        driveFileId: result.id
      });
      console.log(JSON.stringify({ requestId, status: "ok", registration, filename: file.filename, size: file.data.length, duplicate: result.duplicate }));
    } catch (error) {
      const status = error instanceof HttpError ? error.status : 502;
      const message = error instanceof HttpError ? error.message : "Upload gateway failure";
      sendJson(response, status, { requestId, error: message });
      console.error(JSON.stringify({ requestId, status: "error", httpStatus: status, message: error.message }));
    }
  });
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (isMain) {
  try {
    const configuration = loadConfiguration();
    const port = Number(process.env.PORT || 8787);
    const server = createGateway(configuration);
    server.listen(port, "0.0.0.0", () => console.log(`SLM upload gateway listening on port ${port}`));
  } catch (error) {
    console.error(`SLM upload gateway could not start: ${error.message}`);
    process.exitCode = 1;
  }
}
