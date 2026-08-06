#!/usr/bin/env python3
"""
Whisker FCM Credentials Setup Tool (Firebase HTTP v1 API)
==============================================================================
This Python script automates the complete setup of Google Cloud project, IAM, 
Firebase provisioning, and Service Account generation using Google OAuth2 and 
direct Google REST APIs.

No external CLI tools ('gcloud' or 'firebase') are required!

PREREQUISITES:
  - Python 3.7+
  - 'requests' package: pip install requests

USAGE:
  python3 fcm_setup.py
==============================================================================
"""

import requests
import time
import base64
import json
import random
import webbrowser
import urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler

# ==============================================================================
# Configuration
# ==============================================================================
PROJECT_NAME = "Whisker FCM"
SA_NAME = "whisker-push"

# Official Google Cloud SDK Public Client & Secret
CLIENT_ID = "32555940559.apps.googleusercontent.com"
CLIENT_SECRET = "ZmssLNjJy2998hD4CTg2ejr2"

# Removed the invalid firebase scope
SCOPE = "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email"
PORT = 8085
REDIRECT_URI = f"http://localhost:{PORT}"

auth_code = None

def print_deep_error(res):
    """Extracts and pretty-prints deep Google RPC error details if they exist."""
    try:
        err_json = res.json()
        print(json.dumps(err_json, indent=2))
    except Exception:
        print(res.text)

# ==============================================================================
# Step 1: Lightweight Local OAuth Listener
# ==============================================================================
class OAuthHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        global auth_code
        query = urllib.parse.urlparse(self.path).query
        params = urllib.parse.parseqs(query) if hasattr(urllib.parse, "parseqs") else urllib.parse.parse_qs(query)
        
        if "code" in params:
            auth_code = params["code"][0]
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"<h2>Authentication Successful!</h2><p>You can close this window and return to your terminal.</p>")
        else:
            self.send_response(400)
            self.end_headers()

    def log_message(self, format, *args):
        pass # Silence HTTP server logs

def get_cli_access_token():
    print("--- Step 1: Authentication ---")
    server = HTTPServer(("localhost", PORT), OAuthHandler)
    
    auth_url = (
        f"https://accounts.google.com/o/oauth2/v2/auth?"
        f"client_id={CLIENT_ID}&"
        f"redirect_uri={REDIRECT_URI}&"
        f"response_type=code&"
        f"scope={urllib.parse.quote(SCOPE)}&"
        f"access_type=offline&"
        f"prompt=consent"
    )
    
    print(f"- Opening your browser for 1-click Google authorization...")
    print(f"   (If it doesn't open automatically, visit: {auth_url})\n")
    
    webbrowser.open(auth_url)
    server.handle_request()
    
    if not auth_code:
        raise Exception("Failed to capture authorization code from Google.")
    
    token_res = requests.post("https://oauth2.googleapis.com/token", data={
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "code": auth_code,
        "grant_type": "authorization_code",
        "redirect_uri": REDIRECT_URI
    }).json()
    
    if "access_token" not in token_res:
        raise Exception(f"Token exchange failed: {token_res}")
        
    print("* Successfully authenticated!\n")
    return token_res["access_token"]

def request_with_retry(method, url, max_retries=7, delay=8, retry_404=True, **kwargs):
    """Retries HTTP requests if GCP returns 403/404/400/429 due to asynchronous API propagation delays."""
    for attempt in range(max_retries):
        res = requests.request(method, url, **kwargs)
        
        text_lower = res.text.lower()
        
        is_403_retry = res.status_code == 403 and any(msg in text_lower for msg in [
            "has not been used in project", "is disabled", "service_disabled", 
            "user_project_denied", "permission_denied", "the caller does not have permission",
            "not found or permission denied"
        ])
        is_404_retry = retry_404 and res.status_code == 404 and "not found" in text_lower
        is_400_retry = res.status_code == 400 and "not found or deleted" in text_lower
        is_429_retry = res.status_code == 429
        
        if is_429_retry:
            if attempt < max_retries - 1:
                print(f"  [Rate Limit Wait] {method} hit 429 Quota Exceeded. Backing off 20s...")
                time.sleep(20)
                continue

        if is_403_retry or is_404_retry or is_400_retry:
            if attempt < max_retries - 1:
                print(f"  [API Propagation Wait] {method} received HTTP {res.status_code} (attempt {attempt + 1}/{max_retries}). Retrying in {delay}s...")
                time.sleep(delay)
                continue
        return res
    return res

# ==============================================================================
# Master Provisioning Flow
# ==============================================================================
def main():
    token = get_cli_access_token()
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }

    print(f"--- Step 2: Checking GCP Projects for existing Whisker FCM project ---")
    PROJECT_ID = None
    try:
        proj_list_res = requests.get("https://cloudresourcemanager.googleapis.com/v1/projects", headers=headers)
        if proj_list_res.status_code == 200:
            projects = proj_list_res.json().get("projects", [])
            for p in projects:
                if p.get("lifecycleState") == "ACTIVE" and (p.get("projectId", "").startswith("whisker-push-sa-") or p.get("name") == PROJECT_NAME):
                    PROJECT_ID = p.get("projectId")
                    print(f"* Found existing active GCP Project: '{PROJECT_ID}'")
                    break
    except Exception as e:
        print("Warning searching for existing project:", e)

    if PROJECT_ID:
        print(f"Reusing existing GCP Project '{PROJECT_ID}'. Verifying configurations...")
    else:
        RANDOM_SUFFIX = random.randint(100000, 999999)
        PROJECT_ID = f"whisker-push-sa-{RANDOM_SUFFIX}"
        print(f"No existing Whisker FCM project found. Creating new GCP Project '{PROJECT_ID}'...")
        
        res = requests.post("https://cloudresourcemanager.googleapis.com/v1/projects", json={"projectId": PROJECT_ID, "name": PROJECT_NAME}, headers=headers)
        if res.status_code not in [200, 201]:
            print("\n[FATAL] Error creating project:")
            print_deep_error(res)
            return
        
        op_name = res.json().get("name")
        if op_name:
            print(f"Waiting for Google to provision the project backend (Polling status)...")
            for _ in range(20):
                time.sleep(3)
                op_res = requests.get(f"https://cloudresourcemanager.googleapis.com/v1/{op_name}", headers=headers)
                if op_res.status_code == 200:
                    op_data = op_res.json()
                    if op_data.get("done"):
                        if "error" in op_data:
                            print(f"\n[FATAL] Google Cloud rejected project creation:\n{json.dumps(op_data['error'], indent=2)}")
                            return
                        print("* Project provisioned successfully!")
                        break
        else:
            print("Waiting 15 seconds for Google to provision the project backend...")
            time.sleep(15) 

    SA_EMAIL = f"{SA_NAME}@{PROJECT_ID}.iam.gserviceaccount.com"

    print("--- Step 3: Enabling IAM, Firebase, FCM, and API Keys APIs ---")
    services_to_enable = [
        "iam.googleapis.com",
        "firebase.googleapis.com",
        "fcm.googleapis.com",
        "apikeys.googleapis.com",
        "cloudresourcemanager.googleapis.com"
    ]

    try:
        enable_res = request_with_retry(
            "POST", 
            f"https://serviceusage.googleapis.com/v1/projects/{PROJECT_ID}/services:batchEnable",
            json={"serviceIds": services_to_enable},
            headers=headers
        )
        
        if enable_res.status_code in [200, 201, 202]:
            op_name = enable_res.json().get("name")
            if op_name:
                print("Polling batch API enablement operation in parallel...")
                for _ in range(15):
                    time.sleep(3)
                    chk = requests.get(f"https://serviceusage.googleapis.com/v1/{op_name}", headers=headers)
                    if chk.status_code == 200 and chk.json().get("done"):
                        print(f"  - Successfully enabled all {len(services_to_enable)} APIs simultaneously!")
                        break
        else:
            print(f"\n[FATAL] Failed to enable APIs. HTTP {enable_res.status_code}:")
            print_deep_error(enable_res)
            return
    except Exception as e:
        print(f"  - Error batch enabling APIs: {e}")
        return
    
    print("Waiting 15 seconds for API endpoints to finalize globally...")
    time.sleep(15) 

    headers["x-goog-user-project"] = PROJECT_ID

    print("--- Step 4: Adding Firebase to the project ---")
    fb_check = request_with_retry("GET", f"https://firebase.googleapis.com/v1beta1/projects/{PROJECT_ID}", headers=headers, retry_404=False)
    
    if fb_check.status_code == 200:
        print("Firebase is already added/enabled on this project.")
    else:
        fb_res = request_with_retry("POST", f"https://firebase.googleapis.com/v1beta1/projects/{PROJECT_ID}:addFirebase", 
                                    json={}, headers=headers, max_retries=7, delay=15, retry_404=False)
        
        if fb_res.status_code in [200, 202]:
            op_name = fb_res.json().get("name")
            if op_name:
                print("Polling Firebase project initialization...")
                for _ in range(15):
                    time.sleep(3)
                    op_check = request_with_retry("GET", f"https://firebase.googleapis.com/v1beta1/{op_name}", headers=headers, retry_404=False)
                    if op_check.status_code == 200 and op_check.json().get("done"):
                        print("Firebase added to project successfully.")
                        break
        elif fb_res.status_code == 409 or "ALREADY_EXISTS" in fb_res.text or "already exists" in fb_res.text.lower():
            print("Firebase is already added/enabled on this project.")
        else:
            print(f"\n[FATAL] Add Firebase failed with HTTP {fb_res.status_code}:")
            print_deep_error(fb_res)
            return

    print(f"--- Step 5: Creating or Verifying Service Account '{SA_EMAIL}' ---")
    sa_res = request_with_retry("POST", f"https://iam.googleapis.com/v1/projects/{PROJECT_ID}/serviceAccounts", json={
        "accountId": SA_NAME,
        "serviceAccount": {"displayName": "FCM Push Admin"}
    }, headers=headers)
    
    if sa_res.status_code in [200, 201]:
        print(f"Service Account '{SA_EMAIL}' created successfully.")
    elif sa_res.status_code == 409 or "alreadyExists" in sa_res.text or "already exists" in sa_res.text.lower():
        print(f"Service Account '{SA_EMAIL}' already exists.")
    time.sleep(6) 

    print("--- Step 6: Granting root FCM Push roles to the Service Account ---")
    policy_res = request_with_retry("POST", f"https://cloudresourcemanager.googleapis.com/v1/projects/{PROJECT_ID}:getIamPolicy", json={}, headers=headers)
    policy = policy_res.json() if policy_res.status_code == 200 else {}
    
    bindings = policy.get("bindings", [])
    sa_member = f"serviceAccount:{SA_EMAIL}"
    
    # Nuclear Fallback: We grant Editor along with the FCM Admin roles to guarantee beyond a shadow of a doubt it has permissions.
    roles_to_grant = [
        "roles/firebasecloudmessaging.admin", 
        "roles/firebase.admin",
        "roles/firebase.sdkAdminServiceAgent", 
        "roles/editor"
    ]

    for role in roles_to_grant:
        role_binding = next((b for b in bindings if b.get("role") == role), None)
        if role_binding:
            if sa_member not in role_binding.get("members", []):
                role_binding.setdefault("members", []).append(sa_member)
        else:
            bindings.append({"role": role, "members": [sa_member]})
    policy["bindings"] = bindings

    iam_res = request_with_retry("POST", f"https://cloudresourcemanager.googleapis.com/v1/projects/{PROJECT_ID}:setIamPolicy", json={"policy": policy}, headers=headers)
    if iam_res.status_code not in [200, 201]:
        print(f"\n[FATAL] Failed to update IAM Policy (HTTP {iam_res.status_code}):")
        print_deep_error(iam_res)
        return
    
    print("Waiting 45 seconds for Google's global IAM servers to propagate the permissions...")
    print("(If you test sending a push message before this finishes, you will get a 403 Permission Denied!)")
    for i in range(45, 0, -5):
        print(f"  ... {i}s remaining")
        time.sleep(5)

    print("--- Step 7: Generating JSON Key ---")
    key_res = request_with_retry("POST", f"https://iam.googleapis.com/v1/projects/{PROJECT_ID}/serviceAccounts/{SA_EMAIL}/keys", json={}, headers=headers)
    key_data = key_res.json() if key_res.status_code in [200, 201] else {}
    
    project_number = ""
    try:
        proj_res = request_with_retry("GET", f"https://cloudresourcemanager.googleapis.com/v1/projects/{PROJECT_ID}", headers=headers)
        project_number = proj_res.json().get("projectNumber", "") if proj_res.status_code == 200 else ""
    except Exception:
        pass

    print("--- Step 8: Provisioning Android App in Firebase & Retrieving API Key ---")
    api_key, app_id = "", ""
    try:
        apps_res = request_with_retry("GET", f"https://firebase.googleapis.com/v1beta1/projects/{PROJECT_ID}/androidApps", headers=headers, retry_404=False)
        apps_list = apps_res.json().get("apps", []) if apps_res.status_code == 200 else []

        if not apps_list:
            print("No existing Android app found. Creating 'com.example' Android app...")
            create_res = request_with_retry("POST", f"https://firebase.googleapis.com/v1beta1/projects/{PROJECT_ID}/androidApps", 
                                            json={"packageName": "com.example", "displayName": "Whisker Gateway"}, headers=headers, retry_404=False)
            if create_res.status_code in [200, 202]:
                op_name = create_res.json().get("name")
                if op_name:
                    for _ in range(10):
                        time.sleep(3)
                        op_check = request_with_retry("GET", f"https://firebase.googleapis.com/v1beta1/{op_name}", headers=headers, retry_404=False)
                        if op_check.status_code == 200 and op_check.json().get("done"):
                            print("Android app creation completed.")
                            break

            for attempt in range(6):
                time.sleep(3)
                apps_res = request_with_retry("GET", f"https://firebase.googleapis.com/v1beta1/projects/{PROJECT_ID}/androidApps", headers=headers, retry_404=False)
                if apps_res.status_code == 200:
                    apps_list = apps_res.json().get("apps", [])
                    if apps_list: break

        if apps_list:
            app_id = apps_list[0].get("appId", "")
            if app_id:
                cfg_res = request_with_retry("GET", f"https://firebase.googleapis.com/v1beta1/projects/{PROJECT_ID}/androidApps/{app_id}/config", headers=headers, retry_404=False)
                if cfg_res.status_code == 200:
                    clients = cfg_res.json().get("client", [])
                    if clients and clients[0].get("api_key", []):
                        api_key = clients[0]["api_key"][0].get("current_key", "")
    except Exception as e:
        print("Warning during Firebase Android app provisioning:", e)

    # Fallbacks for API Key...
    if not api_key:
        try:
            keys_res = request_with_retry("GET", f"https://apikeys.googleapis.com/v2/projects/{PROJECT_ID}/locations/global/keys", headers=headers)
            if keys_res.status_code == 200:
                for k in keys_res.json().get("keys", []):
                    if k.get("keyString"):
                        api_key = k.get("keyString")
                        break
                    elif k.get("name"):
                        ks_res = request_with_retry("GET", f"https://apikeys.googleapis.com/v2/{k['name']}/keyString", headers=headers)
                        if ks_res.status_code == 200 and ks_res.json().get("keyString"):
                            api_key = ks_res.json().get("keyString")
                            break
        except Exception:
            pass

    if not api_key:
        try:
            create_key_res = request_with_retry("POST", f"https://apikeys.googleapis.com/v2/projects/{PROJECT_ID}/locations/global/keys", json={"displayName": "Whisker Gateway FCM Key"}, headers=headers)
            if create_key_res.status_code in [200, 202]:
                op_name = create_key_res.json().get("name")
                if op_name:
                    for _ in range(10):
                        time.sleep(3)
                        op_res = request_with_retry("GET", f"https://apikeys.googleapis.com/v2/{op_name}", headers=headers)
                        if op_res.status_code == 200 and op_res.json().get("done"):
                            api_key = op_res.json().get("response", {}).get("keyString", "")
                            break
        except Exception:
            pass

    if "privateKeyData" in key_data:
        json_key_str = base64.b64decode(key_data["privateKeyData"]).decode("utf-8")
        try:
            key_dict = json.loads(json_key_str)
            if project_number: key_dict["project_number"] = str(project_number)
            if api_key: key_dict["api_key"] = api_key
            if app_id: key_dict["app_id"] = app_id
            
            with open("whisker-push-sa.json", "w") as f:
                json.dump(key_dict, f, indent=2)
        except Exception:
            with open("whisker-push-sa.json", "w") as f:
                f.write(json_key_str)
            
        print("\n* 'whisker-push-sa.json' has been created in your directory.")
        print(f"Project ID: {PROJECT_ID}")
        if project_number: print(f"Project Number (Sender ID): {project_number}")
        if api_key: print(f"Firebase API Key: {api_key[:6]}...{api_key[-4:] if len(api_key)>10 else ''}")
        if app_id: print(f"Firebase App ID: {app_id}")
            
    else:
        print("\n* Failed to generate key:", key_data)

if __name__ == "__main__":
    main()
