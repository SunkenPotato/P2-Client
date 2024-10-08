package com.sunkenpotato.homebase.web;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sunkenpotato.homebase.MainApplication;
import com.sunkenpotato.homebase.internal.FileItem;
import com.sunkenpotato.homebase.web.response.*;
import javafx.scene.control.Alert;
import okhttp3.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.util.List;

import static com.sunkenpotato.homebase.MainApplication.SETTINGS;
import static com.sunkenpotato.homebase.config.Config.expandTilde;

public class RequestFactory {

    private static final MediaType APPLICATION_JSON = MediaType.parse("application/json");
    private static final RequestFactory INSTANCE = new RequestFactory(SETTINGS.get("server.address").get());
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();
    private final TypeToken<List<FileItem>> FILE_LIST_TYPE_TOKEN = new TypeToken<>() {
    };
    private String BASE_URL;

    private RequestFactory(String url) {
        this.BASE_URL = url;
        this.httpClient = new OkHttpClient();
    }

    public static RequestFactory getInstance() {
        return INSTANCE;
    }

    public void setBASE_URL(String url) {
        this.BASE_URL = url;
    }

    // okhttp so much cleaner ong ong
    @SuppressWarnings("DataFlowIssue")
    public LoginResponse loginRequest(String username, String password) throws IOException {

        UserData body = new UserData(username, password);

        Request request = new Request.Builder().url(BASE_URL + "/user/login").post(RequestBody.create(body.toJSON(), APPLICATION_JSON)).build();

        try (Response response = httpClient.newCall(request).execute()) {
            return switch (response.code()) {
                case 200:
                    yield LoginResponse.okResponse(response.body().string());
                case 403:
                    yield LoginResponse.fromCode(403);
                case 404:
                    yield LoginResponse.fromCode(404);
                default:
                    yield LoginResponse.fromCode(response.code());
            };
        } catch (ConnectException e) {
            return LoginResponse.fromCode(1);
        }
    }

    public CreateUserResponse createUserRequest(String username, String password) throws IOException {
        UserData jsonData = new UserData(username, password);

        Request request = new Request.Builder().url(BASE_URL + "/user/").post(RequestBody.create(jsonData.toJSON(), APPLICATION_JSON)).build();

        try (Response response = httpClient.newCall(request).execute()) {
            return switch (response.code()) {
                case 201:
                    yield CreateUserResponse.OK;
                case 400:
                    yield CreateUserResponse.INVALID_FORM_1;
                case 422:
                    yield CreateUserResponse.INVALID_FORM_2;
                case 409:
                    yield CreateUserResponse.USER_EXISTS;
                case 500:
                    yield CreateUserResponse.SERVER_ERROR;
                default:
                    yield CreateUserResponse.UNKNOWN;
            };
        } catch (ConnectException e) {
            return CreateUserResponse.SERVER_DOWN;
        }
    }

    // IT WORKED
    // note to self:
    // never use apache httpcomponents
    @SuppressWarnings("DataFlowIssue")
    public ListFileResponse listFiles() throws IOException {

        Request request = new Request.Builder().url(BASE_URL + "/file").get().addHeader("Authorization", MainApplication.AUTHORIZATION_TOKEN.getToken()).build();

        try (Response response = httpClient.newCall(request).execute()) {
            return switch (response.code()) {
                case 200 -> {
                    List<FileItem> items = gson.fromJson(response.body().string(), FILE_LIST_TYPE_TOKEN);
                    yield ListFileResponse.okResponse(items);
                }
                case 403 -> {
                    showSessionExpired();
                    System.exit(0);
                    yield null;
                }
                case 404 -> ListFileResponse.fromCode(404);
                default -> ListFileResponse.fromCode(response.code());
            };
        } catch (ConnectException e) {
            throw new ConnectException(e.getMessage());
        }
    }

    @SuppressWarnings("DataFlowIssue")
    // cannot happen ^
    public FileUploadResponse uploadFile(String name, boolean isProtected, File file) throws IOException {
        FileUpload jsonData = new FileUpload(name, isProtected);

        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", file.getName(), RequestBody.create(file, MediaType.parse("multipart/form-data"))).addFormDataPart("json", jsonData.toJSON()).build();

        Request request = new Request.Builder().url(BASE_URL + "/file/").method("POST", body).addHeader("Authorization", MainApplication.AUTHORIZATION_TOKEN.getToken()).build();

        try (Response response = httpClient.newCall(request).execute()) {
            System.out.println(response.message());
            return switch (response.code()) {
                case 400 -> FileUploadResponse.fromCode(400);
                case 404 -> FileUploadResponse.fromCode(404);
                case 403 -> FileUploadResponse.fromCode(403);
                case 500 -> FileUploadResponse.fromCode(500);
                case 200 -> {
                    FileItem responseItem = gson.fromJson(response.body().string(), FileItem.class);
                    yield FileUploadResponse.okResponse(responseItem);
                }
                default -> FileUploadResponse.fromCode(response.code());
            };
        } catch (ConnectException e) {
            return FileUploadResponse.fromCode(1);
        }
    }

    public DownloadFileResponse downloadFile(FileItem fileItem) throws IOException {
        System.out.println(fileItem.route);
        Request request = new Request.Builder().url(BASE_URL + '/' + fileItem.route).get().addHeader("Authorization", MainApplication.AUTHORIZATION_TOKEN.getToken()).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return switch (response.code()) {
                    case 404 -> DownloadFileResponse.NOT_FOUND;
                    case 403 -> {
                        showSessionExpired();
                        yield DownloadFileResponse.SESSION_EXPIRED;
                    }
                    case 500 -> DownloadFileResponse.SERVER_FS_ERROR;
                    default -> DownloadFileResponse.UNKNOWN;
                };
            }

            ResponseBody body = response.body();
            if (body == null) return DownloadFileResponse.EMPTY_BODY;

            try (InputStream in = body.byteStream(); FileOutputStream fs = new FileOutputStream(expandTilde(SETTINGS.get("save.path").get()) + "/" + fileItem.name)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    fs.write(buffer, 0, bytesRead);
                }

                return DownloadFileResponse.OK;
            }

        } catch (ConnectException e) {
            return DownloadFileResponse.CONNECTION_FAILURE;
        }
    }

    public DeleteFileResponse deleteFile(FileItem fileItem) throws IOException {
        Request request = new Request.Builder().url(BASE_URL + '/' + fileItem.route) // TODO: replace w/ string templates or something
                .delete().header("Authorization", MainApplication.AUTHORIZATION_TOKEN.getToken()).build();

        try (Response response = httpClient.newCall(request).execute()) {
            return switch (response.code()) {
                case 404 -> DeleteFileResponse.NOT_FOUND;
                case 403 -> DeleteFileResponse.FORBIDDEN;
                case 500 -> DeleteFileResponse.FS_ERROR;
                case 201 -> DeleteFileResponse.OK;
                default -> DeleteFileResponse.UNKNOWN;
            };
        } catch (ConnectException e) {
            return DeleteFileResponse.CONNECTION_ERROR;
        }
    }

    public void showSessionExpired() {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Session Expired");
        alert.setHeaderText(null);
        alert.setContentText("Session expired. Please close the application and login again.");
        alert.showAndWait();
    }
}
