@echo off
REM Start Admin Catalog on port 5173
start "Admin Catalog" cmd /k "cd /d C:\Users\Akash\Desktop\Projects\apps\admin-product-catalog && npm run dev"

REM Wait 2 seconds before starting the second app
timeout /t 2 /nobreak

REM Start Product Catalog on port 3000
start "Product Catalog" cmd /k "cd /d C:\Users\Akash\Desktop\Projects\apps\product-catalog && npm run dev"

echo Both apps started!
pause
