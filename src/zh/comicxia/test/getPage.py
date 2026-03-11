import requests
from bs4 import BeautifulSoup
import re
import os
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

def main():
    url = "https://www.comicxia.com/read/85692"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": "https://www.comicxia.com/"
    }

    print(f"1. 正在获取网页 HTML: {url}")
    try:
        response = requests.get(url, headers=headers, timeout=10)
        response.raise_for_status()
    except Exception as e:
        print(f"网络请求失败: {e}")
        return

    # 2. 解析 HTML 并定位目标 script 标签
    soup = BeautifulSoup(response.text, 'html.parser')
    scripts = soup.find_all('script')

    target_script_text = None
    # 修改点：直接遍历寻找以特定字符串开头的 script 标签
    for script in scripts:
        if script.string and script.string.strip().startswith('self.__next_f.push([1,"5'):
            target_script_text = script.string
            print("2. 成功定位到目标 script 标签")
            break

    if not target_script_text:
        print("未能找到目标 script 标签，请检查网页 DOM 结构是否发生变化。")
        return

    print("3. 正在解析 JSON 数据和解密配置...")
    
    # 清理 Next.js 数据中烦人的转义符号，方便正则提取
    clean_text = target_script_text.replace('\\"', '"').replace('\\/', '/')

    # 提取解密配置
    key_match = re.search(r'"key":"([^"]+)"', clean_text)
    iv_match = re.search(r'"iv":"([^"]+)"', clean_text)

    if not key_match or not iv_match:
        print("未在数据中找到 key 或 iv 的配置！")
        return

    key = key_match.group(1).encode('utf-8')
    iv = iv_match.group(1).encode('utf-8')
    print(f"   提取到 Key: {key.decode()} | IV: {iv.decode()}")

    # 提取所有的 original_url
    urls = re.findall(r'"original_url":"([^"]+)"', clean_text)
    
    # 去重并保持原有顺序
    urls = list(dict.fromkeys(urls))

    if not urls:
        print("未找到任何图片链接！")
        return

    print(f"4. 共找到 {len(urls)} 张图片，开始下载并解密...")
    save_dir = "downloaded_comics"
    os.makedirs(save_dir, exist_ok=True)

    # 5. 遍历下载并解密
    for idx, img_url in enumerate(urls, 1):
        print(f"   正在处理第 {idx}/{len(urls)} 张: {img_url}")
        try:
            img_res = requests.get(img_url, headers=headers, timeout=15)
            img_res.raise_for_status()
            encrypted_data = img_res.content

            # 初始化 AES 解密器 (CBC 模式, PKCS7 填充)
            cipher = AES.new(key, AES.MODE_CBC, iv)
            
            # 执行解密并移除填充 (unpad)
            decrypted_data = unpad(cipher.decrypt(encrypted_data), AES.block_size)

            # 保存为 webp 文件
            file_path = os.path.join(save_dir, f"page_{idx:03d}.webp")
            with open(file_path, "wb") as f:
                f.write(decrypted_data)
                
        except Exception as e:
            print(f"   -> 下载或解密失败: {e}")

    print("处理完成！请查看 downloaded_comics 文件夹。")

if __name__ == "__main__":
    main()