import ExpoModulesCore
import UIKit
import MobileCoreServices

public class SecureStorageModule: Module, UIDocumentPickerDelegate {
  private var currentPromise: Promise?

  public func definition() -> ModuleDefinition {
    Name("SecureStorage")

    AsyncFunction("pickFile") { promise in
      self.currentPromise = promise
      
      DispatchQueue.main.async {
        // UIDocumentPickerMode.open is key for in-place editing
        let picker = UIDocumentPickerViewController(documentTypes: ["public.item"], in: .open)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        
        guard let root = UIApplication.shared.keyWindow?.rootViewController else {
            promise.reject("NO_UI", "No root view controller")
            return
        }
        root.present(picker, animated: true, completion: nil)
      }
    }

    AsyncFunction("createFile") { (filename: String, promise: Promise) in
      // iOS doesn't have a direct 'create and persist' picker in the same way.
      // Usually you create a local file then export it.
      // For parity, we could mimic 'pickFile' but maybe use a 'Save As' flow if possible
      // For now, let's reject or reuse pickFile to let user pick a folder? 
      // Actually, standard IOS flow is: App creates file -> User exports. 
      // The 'createFile' here implies getting a URI back to write to LATER.
      // iOS Security Scoped Bookmarks on *folders* is possible.
      promise.reject("NOT_IMPLEMENTED", "Direct cloud creation not supported on iOS yet")
    }

    AsyncFunction("readFile") { (urlStr: String, promise: Promise) in
      guard let url = URL(string: urlStr) else {
        promise.reject("ERR_URL", "Invalid URL")
        return
      }

      let accessed = url.startAccessingSecurityScopedResource()
      if !accessed {
         // Note: Some URLs might be readable without this, but generally required for document picker results
         // ignoring failure here if it works anyway? No, safer to fail or warn.
         // Actually, if it fails, it might be a public URL. Proceeding with caution.
      }

      do {
        let data = try Data(contentsOf: url)
        let base64 = data.base64EncodedString()
        promise.resolve(base64)
      } catch {
        promise.reject("ERR_READ", error.localizedDescription)
      }

      if accessed {
          url.stopAccessingSecurityScopedResource()
      }
    }

    AsyncFunction("saveFile") { (urlStr: String, base64Data: String, promise: Promise) in
      guard let url = URL(string: urlStr) else {
        promise.reject("ERR_URL", "Invalid URL")
        return
      }

      // Security Scoped Access
      let accessed = url.startAccessingSecurityScopedResource()
      if !accessed {
         promise.reject("ERR_ACCESS", "Could not access security scoped resource")
         return
      }

      do {
        if let data = Data(base64Encoded: base64Data) {
            try data.write(to: url)
            promise.resolve(nil)
        } else {
            promise.reject("ERR_DATA", "Invalid base64")
        }
      } catch {
        promise.reject("ERR_WRITE", error.localizedDescription)
      }

      if accessed {
          url.stopAccessingSecurityScopedResource()
      }
    }
  }

  // MARK: - UIDocumentPickerDelegate
  public func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
      guard let url = urls.first else {
          currentPromise?.reject("NO_URL", "No URL picked")
          return
      }
      
      // IMPORTANT: Bookmark the URL if persistence is needed across reboots
      // For now, we return the URL string. The .open mode usually grants permission for the session + recents.
      currentPromise?.resolve(url.absoluteString)
      currentPromise = nil
  }

  public func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
      currentPromise?.resolve(nil)
      currentPromise = nil
  }
}
