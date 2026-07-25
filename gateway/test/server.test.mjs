import test from "node:test";
import assert from "node:assert/strict";
import { normalizeRegistration, parseMultipartFile, createGateway } from "../server.mjs";

test("normalizes a glider registration", () => {
  assert.equal(normalizeRegistration(" f-ab12 "), "F-AB12");
});

test("rejects a registration containing a path", () => {
  assert.throws(() => normalizeRegistration("../F-AB12"), /Invalid glider registration/);
});

test("extracts the file from the Android multipart format", () => {
  const boundary = "----SlmBridge123";
  const body = Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="flight.bin"\r\nContent-Type: application/octet-stream\r\n\r\n`, "utf8"),
    Buffer.from([0, 1, 2, 255]),
    Buffer.from(`\r\n--${boundary}--\r\n`, "utf8")
  ]);
  const result = parseMultipartFile(`multipart/form-data; boundary=${boundary}`, body);
  assert.equal(result.filename, "flight.bin");
  assert.deepEqual(result.data, Buffer.from([0, 1, 2, 255]));
});

test("accepts PowerShell multipart parameters without quotes", () => {
  const boundary = "powershellBoundary";
  const body = Buffer.from(`--${boundary}\r\nContent-Type: text/plain\r\nContent-Disposition: form-data; name=file; filename=README.md; filename*=utf-8''README.md\r\n\r\ntest\r\n--${boundary}--\r\n`, "utf8");
  const result = parseMultipartFile(`multipart/form-data; boundary=\"${boundary}\"`, body);
  assert.equal(result.filename, "README.md");
  assert.equal(result.data.toString("utf8"), "test");
});

test("rejects unsafe filenames", () => {
  const boundary = "safeBoundary";
  const body = Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="../secret"\r\n\r\nx\r\n--${boundary}--\r\n`, "utf8");
  assert.throws(() => parseMultipartFile(`multipart/form-data; boundary=${boundary}`, body), /Invalid filename/);
});

test("requires the bearer token and forwards a valid upload", async (context) => {
  const uploads = [];
  const drive = {
    async upload(registration, filename, data, sha256) {
      uploads.push({ registration, filename, data, sha256 });
      return { id: "drive-file-1", duplicate: false };
    }
  };
  const server = createGateway({
    oauth: {},
    uploadToken: "this-is-a-test-token-with-more-than-32-characters",
    maxUploadBytes: 1024
  }, drive);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  context.after(() => new Promise((resolve) => server.close(resolve)));
  const address = server.address();
  const endpoint = `http://127.0.0.1:${address.port}/v1/uploads`;

  const unauthorized = await fetch(endpoint, { method: "POST" });
  assert.equal(unauthorized.status, 401);

  const form = new FormData();
  form.append("file", new Blob([Buffer.from("flight-data")]), "flight.bin");
  const accepted = await fetch(endpoint, {
    method: "POST",
    headers: {
      authorization: "Bearer this-is-a-test-token-with-more-than-32-characters",
      "x-slm-registration": "f-ab12"
    },
    body: form
  });
  assert.equal(accepted.status, 201);
  const result = await accepted.json();
  assert.equal(result.status, "uploaded");
  assert.equal(result.registration, "F-AB12");
  assert.equal(uploads.length, 1);
  assert.equal(uploads[0].filename, "flight.bin");
  assert.equal(uploads[0].data.toString("utf8"), "flight-data");
});
