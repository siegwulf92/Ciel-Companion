import os
import sys
import time
import json
import logging
import traceback
import ctypes
import re

LOG_DIR = r"C:\Ciel Companion\logs"
os.makedirs(LOG_DIR, exist_ok=True)

logger = logging.getLogger("finance_scraper")
logger.setLevel(logging.INFO)
if not logger.handlers:
    handler = logging.FileHandler(os.path.join(LOG_DIR, 'finance_scraper.log'))
    handler.setFormatter(logging.Formatter('%(asctime)s - %(levelname)s - %(message)s'))
    logger.addHandler(handler)

try:
    from playwright.sync_api import sync_playwright
except ImportError as e:
    logger.critical(f"Playwright missing: {e}")
    sys.exit(1)

JAVA_BACKEND_URL = "http://localhost:8081/api/2fa-bridge"
FINANCE_PATH = r"C:\Ciel Companion\ciel\finance"

def get_activity_states():
    """Reads the gaming and media flags passed from Java to determine browser visibility."""
    is_gaming = False
    is_media = False
    if len(sys.argv) > 1:
        is_gaming = str(sys.argv[1]).strip().lower() == "true"
    if len(sys.argv) > 2:
        is_media = str(sys.argv[2]).strip().lower() == "true"
        
    force_headless = is_gaming # Only hide browser entirely if gaming to protect fullscreen
    short_timeout_mode = is_gaming or is_media # Abort quickly if Master is busy
    
    return force_headless, short_timeout_mode

def load_credentials():
    creds = {}
    paths = [
        r"C:\Ciel Companion\src\main\resources\ciel_secrets.properties",
        r"C:\Ciel Companion\ciel_secrets.properties"
    ]
    for path in paths:
        if os.path.exists(path):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if line and not line.startswith("#") and "=" in line:
                            k, v = line.split("=", 1)
                            creds[k.strip()] = v.strip().strip('"').strip("'")
            except: pass
            break
    return creds

def save_financial_data(account_name, balance, positions, roth_progress="Unknown"):
    os.makedirs(FINANCE_PATH, exist_ok=True)
    file_path = os.path.join(FINANCE_PATH, f"{account_name}_data.json")
    data = {
        "account": account_name,
        "total_balance": balance,
        "roth_progress": roth_progress,
        "positions": positions,
        "last_updated": time.strftime("%Y-%m-%d %H:%M:%S")
    }
    with open(file_path, "w") as f:
        json.dump(data, f, indent=4)
    logger.info(f"[{account_name}] Data saved. Roth: {roth_progress}")

def wait_for_manual_intervention(page, site_name, balance_selectors, timeout=120, short_timeout_mode=False):
    if short_timeout_mode:
        logger.warning(f"[{site_name}] Scraper stuck. Master is occupied. Aborting silently.")
        return False
        
    logger.warning(f"[{site_name}] Navigation stuck. Handing over to Master Taylor...")
    ctypes.windll.user32.MessageBoxW(0, f"Ciel Automation Paused.\n\nPlease manually log in using the open browser window. I will wait up to {timeout} seconds for the portfolio balance to appear.", f"Ciel Override: {site_name}", 0x30 | 0x40000)
    
    start_wait = time.time()
    for i in range(timeout):
        for sel in balance_selectors:
            try:
                if page.locator(sel).first.is_visible():
                    elapsed = time.time() - start_wait
                    logger.warning(f"[{site_name}] password change detected, update secrets file.")
                    logger.info(f"[{site_name}] Master Taylor intervened successfully. Took {elapsed:.2f}s.")
                    return True
            except: pass
        time.sleep(1)
        
    logger.error(f"[{site_name}] Manual intervention timed out.")
    return False

def scrape_vanguard(force_headless, short_timeout_mode):
    creds = load_credentials()
    user = creds.get("VANGUARD_USER")
    password = creds.get("VANGUARD_PASS")
    if not user or not password: return

    balance_selectors = [".total-balance", ".portfolio-balance", "[data-testid='total-balance']", "h2:has-text('$')"]
    timeout_ms = 15000 if short_timeout_mode else 45000

    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=force_headless, slow_mo=50, args=["--disable-blink-features=AutomationControlled"])
            page = browser.new_context(viewport={'width': 1920, 'height': 1080}).new_page()
            
            logger.info("[Vanguard] Navigating...")
            page.goto("https://investor.vanguard.com/login", timeout=timeout_ms)
            
            try:
                user_sel = "input[name='USER-ID'], input[type='text'], input[id='user-id']"
                pass_sel = "input[name='PASSWORD-ID'], input[type='password'], input[id='password-id']"
                
                page.locator(user_sel).first.fill(user, timeout=15000)
                
                if not page.locator(pass_sel).first.is_visible():
                    page.locator("button[type='submit'], button:has-text('Next'), button:has-text('Continue')").first.click()
                    page.wait_for_selector(pass_sel, timeout=10000)
                
                page.locator(pass_sel).first.fill(password, timeout=15000)
                page.locator("button[type='submit'], button:has-text('Log In')").first.click()
            except Exception as e:
                logger.warning(f"[Vanguard] Auto-fill failed: {e}")
                
            time.sleep(5)
            
            try:
                if page.locator("input[name='SECURITY-CODE'], input[id='code']").is_visible(timeout=5000) or "security code" in page.content().lower():
                    if short_timeout_mode:
                        logger.warning("[Vanguard] 2FA detected. Master busy. Aborting.")
                        browser.close(); return
                        
                    import requests
                    logger.info("[Vanguard] 2FA detected. Pinging Java UI...")
                    res = requests.post(JAVA_BACKEND_URL, json={"site": "Vanguard"}, timeout=120)
                    code = res.json().get("code")
                    if code:
                        page.locator("input[name='SECURITY-CODE'], input[id='code']").first.fill(code)
                        page.locator("button[type='submit']").first.click()
            except: pass
                    
            balance_visible = False
            for sel in balance_selectors:
                try:
                    if page.locator(sel).first.is_visible(timeout=5000): balance_visible = True; break
                except: pass

            if not balance_visible:
                if not wait_for_manual_intervention(page, "Vanguard", balance_selectors, timeout=120, short_timeout_mode=short_timeout_mode):
                    browser.close(); return
                    
            try:
                balance = "Unknown"
                for sel in balance_selectors:
                    if page.locator(sel).first.is_visible():
                        balance = page.locator(sel).first.inner_text(); break
                save_financial_data("Vanguard", balance, [])
            except: pass
            browser.close()
    except Exception as e:
        logger.error(f"[Vanguard] Crashed:\n{traceback.format_exc()}")

def scrape_stash(force_headless, short_timeout_mode):
    creds = load_credentials()
    user = creds.get("STASH_USER")
    password = creds.get("STASH_PASS")
    if not user or not password: return

    balance_selectors = [".portfolio-value", "[data-testid='portfolio-value']", "h2:has-text('$')", "div:has-text('Total Portfolio')"]
    timeout_ms = 15000 if short_timeout_mode else 45000

    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=force_headless, slow_mo=50, args=["--disable-blink-features=AutomationControlled"])
            page = browser.new_context(viewport={'width': 1920, 'height': 1080}).new_page()
            
            logger.info("[Stash] Navigating...")
            page.goto("https://app.stash.com/login", timeout=timeout_ms)
            
            try:
                user_sel = "input[type='email'], input[name='email']"
                pass_sel = "input[type='password'], input[name='password']"
                
                page.locator(user_sel).first.fill(user, timeout=15000)
                
                if not page.locator(pass_sel).first.is_visible():
                    page.locator("button[type='submit'], button:has-text('Continue')").first.click()
                    page.wait_for_selector(pass_sel, timeout=10000)
                
                page.locator(pass_sel).first.fill(password, timeout=15000)
                page.locator("button[type='submit'], button:has-text('Log In')").first.click()
            except Exception as e:
                logger.warning(f"[Stash] Auto-fill failed: {e}")
                
            time.sleep(5)
            
            try:
                if page.locator("input[name='otp'], input[name='code']").is_visible(timeout=5000) or "verification code" in page.content().lower():
                    if short_timeout_mode:
                        logger.warning("[Stash] 2FA detected. Master busy. Aborting.")
                        browser.close(); return
                        
                    import requests
                    logger.info("[Stash] 2FA detected. Pinging Java UI...")
                    res = requests.post(JAVA_BACKEND_URL, json={"site": "Stash"}, timeout=120)
                    code = res.json().get("code")
                    if code:
                        page.locator("input[name='otp'], input[name='code']").first.fill(code)
                        page.locator("button[type='submit']").first.click()
            except: pass
                    
            balance_visible = False
            for sel in balance_selectors:
                try:
                    if page.locator(sel).first.is_visible(timeout=5000): balance_visible = True; break
                except: pass

            if not balance_visible:
                if not wait_for_manual_intervention(page, "Stash", balance_selectors, timeout=120, short_timeout_mode=short_timeout_mode):
                    browser.close(); return
                    
            balance = "Unknown"
            try:
                for sel in balance_selectors:
                    if page.locator(sel).first.is_visible():
                        balance = page.locator(sel).first.inner_text(); break
            except: pass

            roth_progress = "Unknown"
            try:
                page.goto("https://app.stash.com/retirement", timeout=15000)
                time.sleep(3)
                body_text = page.locator("body").inner_text()
                match = re.search(r'\$[\d,]+\.\d{2}\s*/\s*\$[\d,]+\.\d{2}', body_text) or re.search(r'\$[\d,]+\s*/\s*\$[\d,]+', body_text)
                if match: roth_progress = match.group(0)
            except: pass

            save_financial_data("Stash", balance, [], roth_progress)
            browser.close()
    except Exception as e:
        logger.error(f"[Stash] Crashed:\n{traceback.format_exc()}")

def execute(*args):
    return "This script is triggered externally by Java."

if __name__ == "__main__":
    logger.info("--- Finance Scraper Booting Up ---")
    force_headless, short_timeout_mode = get_activity_states()
    logger.info(f"Environment: Headless = {force_headless} | Short Timeout = {short_timeout_mode}")
    
    scrape_vanguard(force_headless, short_timeout_mode)
    scrape_stash(force_headless, short_timeout_mode)
    logger.info("--- Scraper Execution Complete ---")