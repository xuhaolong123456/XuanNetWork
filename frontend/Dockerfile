FROM node:22-alpine AS frontend-build
WORKDIR /workspace
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=frontend-build /workspace/dist /usr/share/nginx/html
EXPOSE 80
