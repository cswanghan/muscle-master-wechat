FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
# Only API_* placeholders are substituted; nginx's own $host/$uri survive envsubst.
ENV NGINX_ENVSUBST_FILTER=API_
# Defaults target WeChat CloudBase (CLI --envParams is broken: Conf.OperationMode).
# docker-compose overrides both to reach the local `server` container.
ENV API_UPSTREAM=https://muscle-api-297565-11-1469372614.sh.run.tcloudbase.com
ENV API_HOST=muscle-api-297565-11-1469372614.sh.run.tcloudbase.com
COPY default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
