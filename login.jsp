<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="description" content="Sistema de Gestión Documental - Servicio Nacional de Derechos Intelectuales">
    <title>GestionDocumental | Acceso al Sistema</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=Lora:wght@500;700&display=swap" rel="stylesheet">
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        html, body {
            height: 100%;
            width: 100%;
            overflow-x: hidden;
        }

        body {
            font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            background: #eef2f6;
            color: #2c3e50;
            font-size: 16px;
            line-height: 1.6;
            -webkit-font-smoothing: antialiased;
        }

        .login-wrapper {
            display: flex;
            min-height: 100vh;
        }

        /* Sección Izquierda - Branding */
        .brand-section {
            flex: 1.18;
            background:
                linear-gradient(rgba(13, 39, 79, 0.82), rgba(13, 39, 79, 0.82)),
                url('<c:url value="/assets/img/fondo-login.jpg" />') 24% center/cover no-repeat;
            display: flex;
            flex-direction: column;
            justify-content: center;
            align-items: center;
            padding: 60px 40px;
            position: relative;
            color: white;
            overflow: hidden;
        }

        .brand-section::before {
            content: none;
        }

        .brand-section::after {
            content: none;
        }

        .brand-content {
            position: relative;
            z-index: 2;
            text-align: left;
            max-width: 500px;
            background: rgba(10, 30, 61, 0.36);
            border-left: 4px solid rgba(255, 255, 255, 0.52);
            border-radius: 8px;
            padding: 30px 28px;
        }

        .brand-logo {
            width: 88px;
            height: 34px;
            border-radius: 4px;
            border: 1px solid rgba(255, 255, 255, 0.50);
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 12px;
            font-weight: 700;
            letter-spacing: 1px;
            margin-bottom: 20px;
            background: rgba(255, 255, 255, 0.12);
        }

        .brand-content h1 {
            font-family: 'Lora', serif;
            font-size: 40px;
            font-weight: 700;
            margin-bottom: 15px;
            line-height: 1.2;
            letter-spacing: -0.5px;
        }

        .brand-content .subtitle {
            font-size: 14px;
            opacity: 0.9;
            letter-spacing: 2px;
            text-transform: uppercase;
            margin-bottom: 24px;
            font-weight: 500;
        }

        .brand-content p {
            font-size: 16px;
            line-height: 1.65;
            opacity: 0.92;
            margin-bottom: 26px;
        }

        .brand-features {
            display: flex;
            flex-direction: column;
            gap: 14px;
            margin-top: 18px;
        }

        .feature {
            display: flex;
            align-items: flex-start;
            gap: 12px;
            font-size: 15px;
        }

        .feature-icon {
            display: flex;
            width: 26px;
            height: 26px;
            background: rgba(255, 255, 255, 0.24);
            border-radius: 50%;
            justify-content: center;
            align-items: center;
            font-weight: bold;
            flex-shrink: 0;
        }

        .feature-text {
            opacity: 0.9;
        }

        /* Sección Derecha - Formulario */
        .form-section {
            flex: 0.82;
            display: flex;
            justify-content: center;
            align-items: center;
            padding: 30px 34px;
            background: #eef2f6;
        }

        .login-form {
            width: 100%;
            max-width: 440px;
            background: #ffffff;
            border: 1px solid #dbe3ec;
            border-radius: 8px;
            box-shadow: 0 8px 20px rgba(15, 44, 92, 0.08);
            padding: 34px 32px;
        }

        .form-header {
            margin-bottom: 30px;
        }

        .form-header h2 {
            font-family: 'Lora', serif;
            font-size: 31px;
            color: #0f2c5c;
            margin-bottom: 8px;
            font-weight: 700;
        }

        .form-header p {
            color: #7f8c8d;
            font-size: 14px;
            font-weight: 400;
        }

        .form-group {
            margin-bottom: 20px;
        }

        .form-group label {
            display: block;
            margin-bottom: 8px;
            color: #2c3e50;
            font-weight: 600;
            font-size: 13px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }

        .input-wrapper {
            position: relative;
            display: flex;
            align-items: center;
        }

        .input-icon {
            display: none;
        }

        .form-group input {
            width: 100%;
            padding: 13px 14px;
            border: 2px solid #ecf0f1;
            border-radius: 6px;
            font-size: 14px;
            font-family: 'Inter', sans-serif;
            transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
            background: #f8f9fa;
        }

        .form-group input::placeholder {
            color: #95a5a6;
        }

        .form-group input:focus {
            outline: none;
            border-color: #0f2c5c;
            background: white;
            box-shadow: 0 0 0 3px rgba(15, 44, 92, 0.08);
        }

        .form-group input:-webkit-autofill,
        .form-group input:-webkit-autofill:hover,
        .form-group input:-webkit-autofill:focus {
            -webkit-box-shadow: 0 0 0 1000px white inset !important;
            -webkit-text-fill-color: #2c3e50 !important;
        }

        .error-alert {
            background: #fdeaea;
            border: 1px solid #f5b3b3;
            border-left: 4px solid #e74c3c;
            border-radius: 6px;
            padding: 14px 16px;
            margin-bottom: 24px;
            color: #c0392b;
            font-size: 13px;
            animation: slideDown 0.3s ease-out;
        }

        .error-alert strong {
            display: block;
            font-weight: 600;
            margin-bottom: 4px;
        }

        @keyframes slideDown {
            from {
                opacity: 0;
                transform: translateY(-10px);
            }
            to {
                opacity: 1;
                transform: translateY(0);
            }
        }

        .btn-submit {
            width: 100%;
            padding: 13px 16px;
            background: linear-gradient(135deg, #0f2c5c 0%, #1a4d8c 100%);
            color: white;
            border: none;
            border-radius: 6px;
            font-size: 15px;
            font-weight: 600;
            font-family: 'Inter', sans-serif;
            cursor: pointer;
            transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
            text-transform: uppercase;
            letter-spacing: 0.5px;
            margin-top: 10px;
        }

        .btn-submit:hover {
            transform: translateY(-2px);
            box-shadow: 0 8px 24px rgba(15, 44, 92, 0.25);
        }

        .btn-submit:active {
            transform: translateY(0);
        }

        .form-footer {
            margin-top: 26px;
            padding-top: 18px;
            border-top: 1px solid #ecf0f1;
            text-align: center;
            font-size: 12px;
            color: #95a5a6;
        }

        .credentials-info {
            background: #f0f7ff;
            border: 1px solid #d4e6f1;
            border-radius: 6px;
            padding: 14px;
            margin-top: 16px;
            font-size: 12px;
        }

        .credentials-info strong {
            display: block;
            color: #0f2c5c;
            margin-bottom: 8px;
            font-weight: 600;
        }

        .credentials-info div {
            margin: 4px 0;
            color: #2c3e50;
            line-height: 1.5;
        }

        .credentials-info code {
            background: white;
            padding: 2px 6px;
            border-radius: 3px;
            font-family: 'Monaco', monospace;
            font-size: 11px;
        }

        /* Responsive */
        @media (max-width: 1024px) {
            .brand-section {
                flex: 1.05;
                padding: 40px 30px;
            }

            .brand-content h1 {
                font-size: 32px;
            }

            .form-section {
                flex: 0.95;
                padding: 28px 22px;
            }

            .login-form {
                max-width: 410px;
            }
        }

        @media (max-width: 768px) {
            .login-wrapper {
                flex-direction: column;
            }

            .brand-section {
                min-height: 300px;
                padding: 40px 30px;
                flex: auto;
                background-position: center;
            }

            .brand-content {
                max-width: 100%;
                text-align: center;
                padding: 26px 22px;
                backdrop-filter: none;
            }

            .brand-section::before,
            .brand-section::after {
                display: none;
            }

            .brand-features {
                display: none;
            }

            .brand-content h1 {
                font-size: 28px;
            }

            .form-section {
                flex: auto;
                padding: 26px 18px;
            }

            .login-form {
                max-width: 100%;
                padding: 24px 20px;
            }
        }
    </style>
</head>
<body>

    <div class="login-wrapper">
        <!-- Sección Izquierda: Branding -->
        <div class="brand-section">
            <div class="brand-content">
                <div class="brand-logo">SENADI</div>
                <h1>Gestión Documental</h1>
                <div class="subtitle">Plataforma Institucional</div>
                <p>Sistema seguro y confiable para la administración de documentos del Servicio Nacional de Derechos Intelectuales.</p>
                
                <div class="brand-features">
                    <div class="feature">
                        <div class="feature-icon">✓</div>
                        <div class="feature-text">Acceso seguro y autenticado</div>
                    </div>
                    <div class="feature">
                        <div class="feature-icon">✓</div>
                        <div class="feature-text">Gestión centralizada de documentos</div>
                    </div>
                    <div class="feature">
                        <div class="feature-icon">✓</div>
                        <div class="feature-text">Disponible 24/7 para operaciones críticas</div>
                    </div>
                </div>
            </div>
        </div>

        <!-- Sección Derecha: Formulario -->
        <div class="form-section">
            <form action="<c:url value='/login' />" method="post" class="login-form" novalidate>
                <div class="form-header">
                    <h2>Acceso al Sistema</h2>
                    <p>Ingrese sus credenciales para continuar</p>
                </div>

                <c:if test="${not empty error}">
                    <div class="error-alert">
                        <strong>Error de Autenticacion</strong>
                        ${error}
                    </div>
                </c:if>

                <div class="form-group">
                    <label for="username">Usuario</label>
                    <div class="input-wrapper">
                        <input 
                            type="text" 
                            id="username"
                            name="username" 
                            value="${username}" 
                            placeholder="Ingrese su usuario"
                            required
                            autocomplete="username"
                        />
                    </div>
                </div>

                <div class="form-group">
                    <label for="password">Contraseña</label>
                    <div class="input-wrapper">
                        <input 
                            type="password" 
                            id="password"
                            name="password" 
                            placeholder="Ingrese su contraseña"
                            required
                            autocomplete="current-password"
                        />
                    </div>
                </div>

                <button type="submit" class="btn-submit">Acceder</button>

                <div class="form-footer">
                    <div class="credentials-info">
                        <strong>Datos de Prueba:</strong>
                        <div>Admin: <code>admin</code> / <code>1234</code></div>
                        <div>Usuario: <code>user</code> / <code>abcd</code></div>
                    </div>
                    <p style="margin-top: 16px; color: #95a5a6;">© 2026 Servicio Nacional de Derechos Intelectuales</p>
                </div>
            </form>
        </div>
    </div>

</body>
</html>