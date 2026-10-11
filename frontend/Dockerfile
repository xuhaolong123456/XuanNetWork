FROM node:22-alpine AS frontend-build
WORKDIR /workspace
COPY frontend/package*.json ./
RUN npm ci --registry=https://registry.npmjs.org/
COPY frontend/ ./
RUN npm run build

FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/node:22-alpine AS frontend-dev
WORKDIR /workspace/frontend
COPY frontend/package*.json ./
RUN npm ci --registry=https://registry.npmjs.org/
RUN npm config set registry https://registry.npmjs.org/
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]

FROM nginx:1.27-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=frontend-build /workspace/dist /usr/share/nginx/html
EXPOSE 80
