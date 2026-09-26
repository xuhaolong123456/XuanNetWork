# Repository Guidelines

## Project Structure

- `backend/` contains the Java 21, Spring Boot REST API. Application code is under `src/main/java/com/networkdisk`; configuration and profiles are under `src/main/resources`.
- `backend/src/test/java/` contains JUnit 5 tests for authentication, validation, Redis-backed behavior, and security filters.
- `frontend/` contains the Vue 3 and Vite application. Pages live in `src/views`, shared API calls in `src/api`, and global styles in `src/style.css`.
- `docs/` contains API specifications, design notes, and test procedures. `start-dev.bat` starts both local applications.

## Build, Test, and Development

Run commands from the indicated directory:

- Backend: `cd backend; mvn spring-boot:run` starts the API on port 9090.
- Backend tests: `cd backend; mvn test` runs the JUnit suite. `mvn package` builds the application and runs tests.
- Frontend: `cd frontend; npm install` installs dependencies; `npm run dev` starts Vite on port 5173 and proxies `/api` to the backend.
- Frontend build: `cd frontend; npm run build` creates production assets in `frontend/dist`; `npm run preview` serves that build locally.
- `start-dev.bat` is the repository shortcut for starting both services.

## Code Style and Naming

Follow the surrounding code. Use four spaces in Java and two spaces in Vue/JavaScript. Java types use `PascalCase`; methods, fields, and JavaScript functions use `camelCase`. Vue page components use descriptive `PascalCase.vue` names, such as `LoginView.vue`. Keep controllers focused on HTTP handling and place business rules in services. Use Jakarta validation on request DTOs and return the shared `Result` response shape. No formatter or linter is configured; keep imports tidy and avoid unrelated formatting changes.

## Testing Guidelines

Use JUnit 5 and Spring Boot Test. Name test classes `*Test` and group them by package or feature, matching `com.networkdisk.auth` and `com.networkdisk.config`. Add regression coverage for changed authentication behavior, especially token cookies, CSRF checks, and authorization failures. Run `mvn test` before submitting backend changes; run `npm run build` for frontend changes.

## Commits and Pull Requests

The available history contains only the initial commit, so no commit convention is established. Use a short imperative subject, for example `Add HttpOnly authentication cookie`. Pull requests should describe user-visible and API changes, list validation commands and results, link related issues when present, and include screenshots for UI changes.

## Security and Configuration

Never commit credentials, local `application-dev.yml` values, JWT signing secrets, or real tokens. Configure deployment secrets through environment variables. Use HTTPS in production and set `AUTH_COOKIE_SECURE=true`; preserve the CSRF token flow for browser POST requests.
