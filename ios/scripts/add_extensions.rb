# Adds the Screen Time files, entitlements and the two app extensions to the Capacitor-generated
# Xcode project. Idempotent. Run from ios/App:  ruby ../scripts/add_extensions.rb
require 'xcodeproj'
proj = Xcodeproj::Project.open('App.xcodeproj')
app = proj.targets.find { |t| t.name == 'App' } or abort 'App target missing'
IOS = '16.0'
BID = 'com.maatram.app'

def settings(t, h)
  t.build_configurations.each { |c| h.each { |k, v| c.build_settings[k] = v } }
end

proj.build_configurations.each { |c| c.build_settings['IPHONEOS_DEPLOYMENT_TARGET'] = IOS }

# --- main app: plugin sources + entitlements ---
appgrp = proj.main_group['App']
%w[MainViewController.swift AppBlockerPlugin.swift].each do |f|
  next if appgrp.files.any? { |r| r.path == f }
  app.add_file_references([appgrp.new_file(f)])
end
appgrp.new_file('App.entitlements') unless appgrp.files.any? { |r| r.path == 'App.entitlements' }
settings(app, 'CODE_SIGN_ENTITLEMENTS' => 'App/App.entitlements', 'IPHONEOS_DEPLOYMENT_TARGET' => IOS)
%w[FamilyControls ManagedSettings DeviceActivity SwiftUI].each { |f| app.add_system_framework(f) }

# --- extensions ---
exts = [
  { name: 'MaatramMonitor', sub: 'monitor', src: 'MaatramMonitor.swift', fw: %w[DeviceActivity ManagedSettings] },
  { name: 'MaatramShield',  sub: 'shield',  src: 'ShieldConfigurationExtension.swift', fw: %w[ManagedSettings ManagedSettingsUI] },
]
embed = app.copy_files_build_phases.find { |p| p.name == 'Embed Foundation Extensions' } ||
        app.new_copy_files_build_phase('Embed Foundation Extensions').tap { |p| p.dst_subfolder_spec = '13' }

exts.each do |e|
  next if proj.targets.any? { |t| t.name == e[:name] }
  t = proj.new_target(:app_extension, e[:name], :ios, IOS)
  g = proj.main_group.new_group(e[:name], e[:name])
  t.add_file_references([g.new_file(e[:src])])
  g.new_file('Info.plist'); g.new_file("#{e[:name]}.entitlements")
  e[:fw].each { |f| t.add_system_framework(f) }
  settings(t,
    'PRODUCT_BUNDLE_IDENTIFIER' => "#{BID}.#{e[:sub]}",
    'PRODUCT_NAME' => '$(TARGET_NAME)',
    'INFOPLIST_FILE' => "#{e[:name]}/Info.plist",
    'CODE_SIGN_ENTITLEMENTS' => "#{e[:name]}/#{e[:name]}.entitlements",
    'SWIFT_VERSION' => '5.0', 'TARGETED_DEVICE_FAMILY' => '1,2',
    'MARKETING_VERSION' => '1.0', 'CURRENT_PROJECT_VERSION' => '1',
    'SKIP_INSTALL' => 'YES', 'GENERATE_INFOPLIST_FILE' => 'NO',
    'IPHONEOS_DEPLOYMENT_TARGET' => IOS,
    'LD_RUNPATH_SEARCH_PATHS' => '$(inherited) @executable_path/Frameworks @executable_path/../../Frameworks')
  app.add_dependency(t)
  bf = embed.add_file_reference(t.product_reference)
  bf.settings = { 'ATTRIBUTES' => ['RemoveHeadersOnCopy'] }
end

# --- optional manual signing (CI sets these from the provisioning profiles) ---
if ENV['PROFILE_APP']
  { 'App' => 'PROFILE_APP', 'MaatramMonitor' => 'PROFILE_MONITOR', 'MaatramShield' => 'PROFILE_SHIELD' }.each do |tn, ev|
    t = proj.targets.find { |x| x.name == tn }
    settings(t, 'CODE_SIGN_STYLE' => 'Manual', 'DEVELOPMENT_TEAM' => ENV.fetch('TEAM'),
                'CODE_SIGN_IDENTITY' => 'Apple Development', 'PROVISIONING_PROFILE_SPECIFIER' => ENV.fetch(ev))
  end
end
proj.save
puts 'targets: ' + proj.targets.map(&:name).join(', ')
