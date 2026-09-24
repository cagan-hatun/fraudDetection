# Build stage
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
# openapi-typescript henüz TypeScript 6'yı peer dep olarak tanımıyor (bkz. README).
RUN npm ci --legacy-peer-deps

COPY . .
# Vite, VITE_* değişkenlerini BUILD ZAMANINDA koda gömer (runtime'da okunmaz) —
# bu yüzden ENV değil, build ARG olarak veriliyor. Değer varsayılan olarak
# backend'in docker-compose'daki host'a açılan portunu gösteriyor: tarayıcıda
# çalışan bu kod backend'e container ağı üzerinden değil, kendi makinemizden
# (localhost) erişiyor.
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# Runtime stage
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
